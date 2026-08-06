package com.cobre.notifications.application.service;

import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase.IngestCommand;
import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase.IngestResult;
import com.cobre.notifications.application.port.out.DeliveryQueuePort;
import com.cobre.notifications.application.port.out.MetricsPort;
import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.domain.model.NotificationEvent;
import com.cobre.notifications.domain.model.WebhookDeliveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La ingesta vive en el consumidor: es lo unico que hace ese componente, traducir un
 * mensaje del bus en un comando y encolar la entrega.
 */
class IngestNotificationEventServiceTest {

    private static final String EVENT_ID = "EVT001";
    private static final String CLIENT_ID = "CLIENT002";
    private static final Instant NOW = Instant.parse("2024-03-15T16:10:00Z");

    private final NotificationEventRepositoryPort events = mock(NotificationEventRepositoryPort.class);
    private final DeliveryQueuePort deliveryQueue = mock(DeliveryQueuePort.class);
    private final MetricsPort metrics = mock(MetricsPort.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);


    private IngestNotificationEventService service;

    @BeforeEach
    void setUp() {
        service = new IngestNotificationEventService(events, deliveryQueue, metrics, clock);
    }

    @Test
    @DisplayName("un evento nuevo se persiste y se encola para entrega")
    void acepta_evento_nuevo() {
        when(events.insertIfAbsent(any())).thenReturn(Mono.just(true));
        when(deliveryQueue.enqueue(EVENT_ID, CLIENT_ID)).thenReturn(Mono.empty());

        IngestCommand command = new IngestCommand(
                EVENT_ID, CLIENT_ID, "credit_transfer", "Transferencia", NOW.minusSeconds(2));

        StepVerifier.create(service.ingest(command))
                .expectNext(IngestResult.ACCEPTED)
                .verifyComplete();

        verify(deliveryQueue).enqueue(EVENT_ID, CLIENT_ID);
        verify(metrics).eventIngested("credit_transfer");
    }

    @Test
    @DisplayName("una reentrega del broker no genera una segunda entrega al cliente")
    void ignora_duplicados() {
        when(events.insertIfAbsent(any())).thenReturn(Mono.just(false));
        // La reentrega tipica: el evento ya paso por su ciclo y esta cerrado.
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(
                NotificationEvent.received(EVENT_ID, CLIENT_ID, "credit_transfer", "Transferencia", NOW, NOW)
                        .markDelivered(WebhookDeliveryResult.delivered(200, 12), NOW)));

        IngestCommand command = new IngestCommand(
                EVENT_ID, CLIENT_ID, "credit_transfer", "Transferencia", NOW.minusSeconds(2));

        StepVerifier.create(service.ingest(command))
                .expectNext(IngestResult.DUPLICATE_IGNORED)
                .verifyComplete();

        verify(deliveryQueue, never()).enqueue(anyString(), anyString());
        verify(metrics, never()).eventIngested(anyString());
    }

    @Test
    @DisplayName("si el evento llega sin fecha de creacion se usa la de recepcion")
    void suple_la_fecha_de_creacion() {
        when(events.insertIfAbsent(any())).thenReturn(Mono.just(true));
        when(deliveryQueue.enqueue(EVENT_ID, CLIENT_ID)).thenReturn(Mono.empty());

        IngestCommand command = new IngestCommand(
                EVENT_ID, CLIENT_ID, "credit_transfer", "Transferencia", null);

        StepVerifier.create(service.ingest(command))
                .expectNext(IngestResult.ACCEPTED)
                .verifyComplete();

        ArgumentCaptor<NotificationEvent> saved = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(events).insertIfAbsent(saved.capture());
        assertThat(saved.getValue().createdAt()).isEqualTo(NOW);
    }


    @Test
    @DisplayName("un evento que ya existia pero sigue sin entregarse se reencola, no se ignora")
    void rescata_el_evento_varado() {
        when(events.insertIfAbsent(any())).thenReturn(Mono.just(false));
        NotificationEvent varado = NotificationEvent.received(
                EVENT_ID, CLIENT_ID, "credit_transfer", "Transferencia", NOW, NOW);
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(varado));
        when(deliveryQueue.enqueue(EVENT_ID, CLIENT_ID)).thenReturn(Mono.empty());

        IngestCommand command = new IngestCommand(
                EVENT_ID, CLIENT_ID, "credit_transfer", "Transferencia", NOW.minusSeconds(2));

        StepVerifier.create(service.ingest(command))
                .expectNext(IngestResult.ACCEPTED)
                .verifyComplete();

        // Sin esto, un evento cuyo encolado fallo en el intento anterior queda escrito
        // en base y sin mensaje en cola: nadie lo entrega nunca.
        verify(deliveryQueue).enqueue(EVENT_ID, CLIENT_ID);
    }

    @Test
    @DisplayName("un evento ya entregado no se reencola: seria una segunda notificacion al cliente")
    void no_reencola_lo_ya_cerrado() {
        when(events.insertIfAbsent(any())).thenReturn(Mono.just(false));
        NotificationEvent entregado = NotificationEvent.received(
                EVENT_ID, CLIENT_ID, "credit_transfer", "Transferencia", NOW, NOW)
                .markDelivered(WebhookDeliveryResult.delivered(200, 12), NOW);
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(entregado));

        IngestCommand command = new IngestCommand(
                EVENT_ID, CLIENT_ID, "credit_transfer", "Transferencia", NOW.minusSeconds(2));

        StepVerifier.create(service.ingest(command))
                .expectNext(IngestResult.DUPLICATE_IGNORED)
                .verifyComplete();

        verify(deliveryQueue, never()).enqueue(anyString(), anyString());
    }
}
