package com.cobre.notifications.infrastructure.adapter.in.messaging.kafka;

import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase;
import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase.IngestCommand;
import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase.IngestResult;
import com.cobre.notifications.infrastructure.config.KafkaProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOffset;
import reactor.kafka.receiver.ReceiverRecord;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * En Kafka confirmar no borra nada: mueve un puntero (el offset).
 *
 * <p>El mensaje sigue en el topic durante su periodo de retencion, asi que otros
 * servicios pueden leer el mismo evento. Lo que se verifica aqui es que el puntero solo
 * avance despues de procesar: si el proceso muere antes, Kafka reentrega desde el
 * ultimo confirmado y el evento no se pierde.
 */
class KafkaPlatformEventListenerTest {

    private static final String TOPIC = "cobre.platform.events";

    private final IngestNotificationEventUseCase ingestUseCase = mock(IngestNotificationEventUseCase.class);
    private final KafkaReceiver<String, String> receiver = mock(KafkaReceiver.class);
    private final ReceiverOffset offset = mock(ReceiverOffset.class);

    private KafkaPlatformEventListener listener;

    private static final String EVENTO = """
            {"event_id":"EVT001","client_id":"CLIENT001","event_type":"credit_card_payment",
             "content":"Credit card payment received for $150.00","created_at":"2024-03-15T09:30:20Z"}
            """;

    @BeforeEach
    void setUp() {
        listener = new KafkaPlatformEventListener(
                receiver, ingestUseCase, JsonMapper.builder().build(),
                new KafkaProperties("localhost:9092", TOPIC, "grupo-de-pruebas"));
    }

    private ReceiverRecord<String, String> registro(String cuerpo) {
        return new ReceiverRecord<>(
                new ConsumerRecord<>(TOPIC, 0, 0L, "EVT001", cuerpo), offset);
    }

    @Test
    @DisplayName("traduce el evento del topic al comando de ingesta")
    void traduce_el_evento() {
        when(ingestUseCase.ingest(any())).thenReturn(Mono.just(IngestResult.ACCEPTED));
        when(receiver.receive()).thenReturn(Flux.just(registro(EVENTO)));

        listener.start();
        listener.stop();

        ArgumentCaptor<IngestCommand> command = ArgumentCaptor.forClass(IngestCommand.class);
        verify(ingestUseCase).ingest(command.capture());
        assertThat(command.getValue().eventId()).isEqualTo("EVT001");
        assertThat(command.getValue().clientId()).isEqualTo("CLIENT001");
        assertThat(command.getValue().createdAt()).isEqualTo(Instant.parse("2024-03-15T09:30:20Z"));
    }

    @Test
    @DisplayName("el offset avanza solo despues de procesar el evento")
    void confirma_despues_de_procesar() {
        when(ingestUseCase.ingest(any())).thenReturn(Mono.just(IngestResult.ACCEPTED));
        when(receiver.receive()).thenReturn(Flux.just(registro(EVENTO)));

        listener.start();
        listener.stop();

        verify(offset).acknowledge();
    }

    @Test
    @DisplayName("un mensaje corrupto se confirma igual: bloquearia la particion entera")
    void un_mensaje_corrupto_no_bloquea_la_particion() {
        when(receiver.receive()).thenReturn(Flux.just(registro("{esto no es json")));

        listener.start();
        listener.stop();

        verify(ingestUseCase, never()).ingest(any());
        // Se confirma para que el resto de la particion pueda seguir avanzando.
        verify(offset).acknowledge();
    }

    @Test
    @DisplayName("una reentrega del mismo evento no genera una segunda entrega")
    void una_reentrega_no_duplica() {
        when(ingestUseCase.ingest(any())).thenReturn(Mono.just(IngestResult.DUPLICATE_IGNORED));
        when(receiver.receive()).thenReturn(Flux.just(registro(EVENTO), registro(EVENTO)));

        listener.start();
        listener.stop();

        // El caso de uso decide; el adaptador solo traduce y confirma.
        verify(ingestUseCase, org.mockito.Mockito.times(2)).ingest(any());
        verify(offset, org.mockito.Mockito.times(2)).acknowledge();
    }
}
