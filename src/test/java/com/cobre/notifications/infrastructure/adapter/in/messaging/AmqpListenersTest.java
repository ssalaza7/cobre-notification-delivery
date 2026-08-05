package com.cobre.notifications.infrastructure.adapter.in.messaging;

import com.cobre.notifications.application.port.in.DeliverNotificationEventUseCase;
import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase;
import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase.IngestCommand;
import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase.IngestResult;
import com.cobre.notifications.domain.exception.NotificationEventNotFoundException;
import com.cobre.notifications.domain.model.DeliveryOutcome;
import com.cobre.notifications.infrastructure.config.MessagingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.Receiver;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AmqpListenersTest {

    private static final MessagingProperties MESSAGING = new MessagingProperties(
            "cobre.platform.events", "cobre.notifications.inbound", "#",
            "cobre.notifications", "cobre.notifications.delivery", "delivery",
            "cobre.notifications.retry", "cobre.notifications.dlq", "dead", 8);

    private final IngestNotificationEventUseCase ingestUseCase = mock(IngestNotificationEventUseCase.class);
    private final DeliverNotificationEventUseCase deliverUseCase = mock(DeliverNotificationEventUseCase.class);
    private final Receiver receiver = mock(Receiver.class);

    private PlatformEventListener platformListener() {
        return new PlatformEventListener(receiver, MESSAGING, ingestUseCase, JsonMapper.builder().build());
    }

    private DeliveryCommandListener deliveryListener() {
        return new DeliveryCommandListener(receiver, MESSAGING, deliverUseCase, JsonMapper.builder().build());
    }

    private AcknowledgableDelivery deliveryWith(String body) {
        AcknowledgableDelivery delivery = mock(AcknowledgableDelivery.class);
        when(delivery.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return delivery;
    }

    @Test
    @DisplayName("un evento de la plataforma se traduce al comando de ingesta")
    void traduce_el_evento_de_plataforma() {
        when(ingestUseCase.ingest(any())).thenReturn(Mono.just(IngestResult.ACCEPTED));

        String json = """
                {"event_id":"EVT001","client_id":"CLIENT001","event_type":"credit_card_payment",
                 "content":"Credit card payment received for $150.00","created_at":"2024-03-15T09:30:20Z"}
                """;

        StepVerifier.create(platformListener().handle(json)).verifyComplete();

        ArgumentCaptor<IngestCommand> command = ArgumentCaptor.forClass(IngestCommand.class);
        verify(ingestUseCase).ingest(command.capture());
        assertThat(command.getValue().eventId()).isEqualTo("EVT001");
        assertThat(command.getValue().clientId()).isEqualTo("CLIENT001");
        assertThat(command.getValue().eventType()).isEqualTo("credit_card_payment");
        assertThat(command.getValue().createdAt()).isEqualTo(Instant.parse("2024-03-15T09:30:20Z"));
    }

    @Test
    @DisplayName("un mensaje corrupto falla y termina en la cola muerta, no en un bucle de reintentos")
    void un_mensaje_corrupto_falla() {
        StepVerifier.create(platformListener().handle("{esto no es json"))
                .expectError()
                .verify();

        verify(ingestUseCase, never()).ingest(any());
    }

    @Test
    @DisplayName("una orden de entrega invoca el caso de uso con el identificador del mensaje")
    void ejecuta_la_orden_de_entrega() {
        when(deliverUseCase.deliver("EVT001")).thenReturn(Mono.just(DeliveryOutcome.DELIVERED));

        StepVerifier.create(deliveryListener().handle("{\"event_id\":\"EVT001\",\"client_id\":\"CLIENT001\"}")).verifyComplete();

        verify(deliverUseCase).deliver("EVT001");
    }

    @Test
    @DisplayName("una orden para un evento inexistente se descarta sin ensuciar la cola muerta")
    void descarta_ordenes_huerfanas() {
        when(deliverUseCase.deliver("EVT404"))
                .thenReturn(Mono.error(new NotificationEventNotFoundException("EVT404")));

        StepVerifier.create(deliveryListener().handle("{\"event_id\":\"EVT404\",\"client_id\":\"CLIENT001\"}")).verifyComplete();
    }

    @Test
    @DisplayName("el mensaje se confirma solo despues de que el caso de uso termino")
    void confirma_despues_de_procesar() {
        when(deliverUseCase.deliver("EVT001")).thenReturn(Mono.just(DeliveryOutcome.DELIVERED));
        AcknowledgableDelivery delivery = deliveryWith("{\"event_id\":\"EVT001\",\"client_id\":\"CLIENT001\"}");
        when(receiver.consumeManualAck(anyString(), any(ConsumeOptions.class)))
                .thenReturn(Flux.just(delivery));

        DeliveryCommandListener listener = deliveryListener();
        listener.start();
        listener.stop();

        verify(delivery).ack();
        verify(delivery, never()).nack(anyBoolean());
    }

    @Test
    @DisplayName("un mensaje que falla se rechaza sin reencolar para no volverse un bucle infinito")
    void rechaza_sin_reencolar() {
        AcknowledgableDelivery delivery = deliveryWith("{no es json}");
        when(receiver.consumeManualAck(anyString(), any(ConsumeOptions.class)))
                .thenReturn(Flux.just(delivery));

        PlatformEventListener listener = platformListener();
        listener.start();
        listener.stop();

        verify(delivery).nack(false);
        verify(delivery, never()).ack();
    }

    @Test
    @DisplayName("cada escucha se engancha a su propia cola")
    void escucha_su_cola() {
        when(receiver.consumeManualAck(anyString(), any(ConsumeOptions.class))).thenReturn(Flux.empty());

        platformListener().start();
        deliveryListener().start();

        verify(receiver).consumeManualAck(eqQueue("cobre.notifications.inbound"), any(ConsumeOptions.class));
        verify(receiver).consumeManualAck(eqQueue("cobre.notifications.delivery"), any(ConsumeOptions.class));
    }

    private static String eqQueue(String queue) {
        return org.mockito.ArgumentMatchers.eq(queue);
    }
}
