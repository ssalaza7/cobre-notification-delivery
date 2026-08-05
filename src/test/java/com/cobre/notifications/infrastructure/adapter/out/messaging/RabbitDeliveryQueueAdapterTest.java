package com.cobre.notifications.infrastructure.adapter.out.messaging;

import com.cobre.notifications.infrastructure.config.MessagingProperties;
import com.cobre.notifications.infrastructure.config.RetryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.Sender;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@SuppressWarnings({"rawtypes", "unchecked"})
class RabbitDeliveryQueueAdapterTest {

    private static final MessagingProperties MESSAGING = new MessagingProperties(
            "cobre.platform.events", "cobre.notifications.inbound", "#",
            "cobre.notifications", "cobre.notifications.delivery", "delivery",
            "cobre.notifications.retry", "cobre.notifications.dlq", "dead", 64);

    private static final RetryProperties RETRY = new RetryProperties(
            5,
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2)),
            0.2);

    private final Sender sender = mock(Sender.class);
    private final List<OutboundMessage> published = new ArrayList<>();
    private RabbitDeliveryQueueAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RabbitDeliveryQueueAdapter(sender, MESSAGING, RETRY, JsonMapper.builder().build());
        acknowledgeEverything(true);
    }

    /** doAnswer y no when(...): re-stubbear con when invocaria el answer anterior con un argumento nulo. */
    private void acknowledgeEverything(boolean ack) {
        doAnswer(call -> {
            Publisher<OutboundMessage> messages = call.getArgument(0);
            return Flux.from(messages).map(message -> {
                published.add(message);
                return new OutboundMessageResult(message, ack);
            });
        }).when(sender).sendWithPublishConfirms(any());
    }

    @Test
    @DisplayName("una entrega nueva se publica en la cola de entrega, persistente")
    void publica_la_entrega() {
        StepVerifier.create(adapter.enqueue("EVT001", "CLIENT001")).verifyComplete();

        assertThat(published).hasSize(1);
        OutboundMessage message = published.get(0);
        assertThat(message.getExchange()).isEqualTo("cobre.notifications");
        assertThat(message.getRoutingKey()).isEqualTo("delivery");
        assertThat(message.getProperties().getDeliveryMode()).isEqualTo(2);
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains("\"event_id\":\"EVT001\"");
    }

    @Test
    @DisplayName("un reintento va a la cola de retardo de su escalon, con TTL por mensaje")
    void publica_el_reintento_en_su_escalon() {
        StepVerifier.create(adapter.enqueueRetry("EVT001", "CLIENT001", Duration.ofSeconds(33))).verifyComplete();

        OutboundMessage message = published.get(0);
        assertThat(message.getExchange()).isEqualTo("cobre.notifications.retry");
        // 33s cae en el escalon de 30s: es 30s con jitter aplicado, no el de 2 minutos.
        assertThat(message.getRoutingKey()).isEqualTo("retry.30s");
        assertThat(message.getProperties().getExpiration()).isEqualTo("33000");
    }

    @Test
    @DisplayName("una espera menor al primer escalon usa el primer escalon")
    void usa_el_primer_escalon_como_piso() {
        StepVerifier.create(adapter.enqueueRetry("EVT001", "CLIENT001", Duration.ofSeconds(1))).verifyComplete();

        assertThat(published.get(0).getRoutingKey()).isEqualTo("retry.5s");
    }

    @Test
    @DisplayName("una espera mayor al ultimo escalon usa el ultimo")
    void usa_el_ultimo_escalon_como_techo() {
        StepVerifier.create(adapter.enqueueRetry("EVT001", "CLIENT001", Duration.ofHours(1))).verifyComplete();

        assertThat(published.get(0).getRoutingKey()).isEqualTo("retry.120s");
    }

    @Test
    @DisplayName("lo que ya no se reintenta va a la cola muerta con el motivo")
    void publica_en_la_cola_muerta() {
        StepVerifier.create(adapter.sendToDeadLetter("EVT001", "CLIENT001", "reintentos agotados")).verifyComplete();

        OutboundMessage message = published.get(0);
        assertThat(message.getRoutingKey()).isEqualTo("dead");
        assertThat(message.getProperties().getHeaders())
                .containsEntry("x-cobre-dead-letter-reason", "reintentos agotados");
    }

    @Test
    @DisplayName("si el broker no confirma, la publicacion falla y el mensaje original no se confirma")
    void falla_si_el_broker_no_confirma() {
        published.clear();
        acknowledgeEverything(false);

        StepVerifier.create(adapter.enqueue("EVT001", "CLIENT001"))
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("rechazo la publicacion"))
                .verify();
    }
}
