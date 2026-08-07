package com.cobre.notifications.application.service;

import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase.IngestCommand;
import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase.IngestResult;
import com.cobre.notifications.application.port.out.DeliveryAttemptRepositoryPort;
import com.cobre.notifications.application.port.out.DeliveryQueuePort;
import com.cobre.notifications.application.port.out.MetricsPort;
import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.domain.exception.NotificationEventNotFoundException;
import com.cobre.notifications.domain.exception.ReplayNotAllowedException;
import com.cobre.notifications.domain.model.AttemptOutcome;
import com.cobre.notifications.domain.model.DeliveryAttempt;
import com.cobre.notifications.domain.model.DeliveryStatus;
import com.cobre.notifications.domain.model.EventQuery;
import com.cobre.notifications.domain.model.NotificationEvent;
import com.cobre.notifications.domain.model.PageResult;
import com.cobre.notifications.domain.model.WebhookDeliveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelfServiceUseCasesTest {

    private static final String EVENT_ID = "EVT003";
    private static final String CLIENT_ID = "CLIENT002";
    private static final Instant NOW = Instant.parse("2024-03-15T12:00:00Z");

    private final NotificationEventRepositoryPort events = mock(NotificationEventRepositoryPort.class);
    private final DeliveryAttemptRepositoryPort attempts = mock(DeliveryAttemptRepositoryPort.class);
    private final DeliveryQueuePort deliveryQueue = mock(DeliveryQueuePort.class);
    private final MetricsPort metrics = mock(MetricsPort.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private NotificationEvent event(DeliveryStatus status) {
        NotificationEvent base = NotificationEvent.received(
                EVENT_ID, CLIENT_ID, "credit_transfer", "Transferencia", NOW.minusSeconds(2), NOW);
        return switch (status) {
            case COMPLETED -> base.markDelivered(WebhookDeliveryResult.delivered(200, 10), NOW);
            case FAILED -> base.markFailed(WebhookDeliveryResult.retryable(503, "caido", 10), NOW);
            case DISCARDED -> base.markDiscarded("sin suscripcion", NOW);
            case RETRYING -> base.markRetrying(WebhookDeliveryResult.retryable(503, "caido", 10), NOW);
            case PENDING -> base;
        };
    }

    @Nested
    @DisplayName("Listado de notificaciones")
    class Query {

        @Test
        @DisplayName("devuelve la pagina junto con el cursor para pedir la siguiente")
        void devuelve_pagina_y_cursor() {
            EventQuery query = new EventQuery(CLIENT_ID, null, null, DeliveryStatus.FAILED, null, 20);
            when(events.search(query)).thenReturn(Mono.just(
                    new PageResult<>(List.of(event(DeliveryStatus.FAILED)), 20, "siguiente")));

            StepVerifier.create(new QueryNotificationEventsService(events).query(query))
                    .assertNext(page -> {
                        assertThat(page.items()).hasSize(1);
                        assertThat(page.nextCursor()).isEqualTo("siguiente");
                        assertThat(page.hasNext()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("la ultima pagina no lleva cursor")
        void ultima_pagina_sin_cursor() {
            EventQuery query = new EventQuery(CLIENT_ID, null, null, null, "anterior", 20);
            when(events.search(query)).thenReturn(Mono.just(
                    new PageResult<>(List.of(event(DeliveryStatus.COMPLETED)), 20, null)));

            StepVerifier.create(new QueryNotificationEventsService(events).query(query))
                    .assertNext(page -> assertThat(page.hasNext()).isFalse())
                    .verifyComplete();
        }

        @Test
        @DisplayName("un cliente sin notificaciones recibe una pagina vacia, no un error")
        void pagina_vacia() {
            EventQuery query = new EventQuery("CLIENT999", null, null, null, null, 20);
            when(events.search(query)).thenReturn(Mono.just(new PageResult<>(List.of(), 20, null)));

            StepVerifier.create(new QueryNotificationEventsService(events).query(query))
                    .assertNext(page -> {
                        assertThat(page.items()).isEmpty();
                        assertThat(page.hasNext()).isFalse();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Detalle de una notificacion")
    class Detail {

        private GetNotificationEventService service;

        @BeforeEach
        void setUp() {
            service = new GetNotificationEventService(events, attempts);
        }

        @Test
        @DisplayName("adjunta la bitacora completa de intentos")
        void devuelve_detalle_con_intentos() {
            when(events.findByIdAndClientId(EVENT_ID, CLIENT_ID))
                    .thenReturn(Mono.just(event(DeliveryStatus.FAILED)));
            when(attempts.findByEventId(EVENT_ID)).thenReturn(Flux.just(new DeliveryAttempt(
                    UUID.randomUUID(), EVENT_ID, 1, 0, NOW,
                    AttemptOutcome.RETRYABLE_FAILURE, 503, 5000, "caido")));

            StepVerifier.create(service.get(EVENT_ID, CLIENT_ID))
                    .assertNext(detail -> {
                        assertThat(detail.event().eventId()).isEqualTo(EVENT_ID);
                        assertThat(detail.attempts()).hasSize(1);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("una notificacion de otro cliente es indistinguible de una inexistente")
        void oculta_recursos_ajenos() {
            when(events.findByIdAndClientId(EVENT_ID, "CLIENT999")).thenReturn(Mono.empty());

            StepVerifier.create(service.get(EVENT_ID, "CLIENT999"))
                    .expectError(NotificationEventNotFoundException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("Reenvio de una notificacion")
    class Replay {

        private ReplayNotificationEventService service;

        @BeforeEach
        void setUp() {
            service = new ReplayNotificationEventService(events, deliveryQueue, metrics, clock);
            // update(previous, updated) devuelve el evento ya transicionado.
            when(events.update(any(), any())).thenAnswer(call -> Mono.just(call.getArgument(1)));
            when(deliveryQueue.enqueue(anyString(), anyString())).thenReturn(Mono.empty());
        }

        @Test
        @DisplayName("un fallo definitivo se reabre y se encola sin entregar en linea")
        void reencola_un_fallo_definitivo() {
            when(events.findByIdAndClientId(EVENT_ID, CLIENT_ID))
                    .thenReturn(Mono.just(event(DeliveryStatus.FAILED)));

            StepVerifier.create(service.replay(EVENT_ID, CLIENT_ID))
                    .assertNext(reopened -> {
                        assertThat(reopened.deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
                        assertThat(reopened.attempts()).isZero();
                        assertThat(reopened.replayCount()).isEqualTo(1);
                    })
                    .verifyComplete();

            verify(deliveryQueue).enqueue(EVENT_ID, CLIENT_ID);
            verify(metrics).replayRequested("credit_transfer");
        }

        @Test
        @DisplayName("reenviar algo ya entregado se rechaza para no duplicar la notificacion")
        void rechaza_reenvio_de_entregada() {
            when(events.findByIdAndClientId(EVENT_ID, CLIENT_ID))
                    .thenReturn(Mono.just(event(DeliveryStatus.COMPLETED)));

            StepVerifier.create(service.replay(EVENT_ID, CLIENT_ID))
                    .expectError(ReplayNotAllowedException.class)
                    .verify();

            verify(deliveryQueue, never()).enqueue(anyString(), anyString());
        }

        @Test
        @DisplayName("reenviar algo todavia en curso se rechaza: ya hay un reintento programado")
        void rechaza_reenvio_en_curso() {
            when(events.findByIdAndClientId(EVENT_ID, CLIENT_ID))
                    .thenReturn(Mono.just(event(DeliveryStatus.RETRYING)));

            StepVerifier.create(service.replay(EVENT_ID, CLIENT_ID))
                    .expectError(ReplayNotAllowedException.class)
                    .verify();
        }

        @Test
        @DisplayName("no se puede reenviar una notificacion de otro cliente")
        void rechaza_reenvio_ajeno() {
            when(events.findByIdAndClientId(EVENT_ID, "CLIENT999")).thenReturn(Mono.empty());

            StepVerifier.create(service.replay(EVENT_ID, "CLIENT999"))
                    .expectError(NotificationEventNotFoundException.class)
                    .verify();
        }

        @Test
        @DisplayName("si dos reenvios compiten, solo uno encola la entrega")
        void resuelve_carrera_de_reenvios() {
            when(events.findByIdAndClientId(EVENT_ID, CLIENT_ID))
                    .thenReturn(Mono.just(event(DeliveryStatus.FAILED)));
            // doReturn y no when(...): re-stubbear con when invocaria el answer anterior.
            doReturn(Mono.empty()).when(events).update(any(), any());

            StepVerifier.create(service.replay(EVENT_ID, CLIENT_ID))
                    .expectError(NotificationEventNotFoundException.class)
                    .verify();

            verify(deliveryQueue, never()).enqueue(anyString(), anyString());
        }
    }
}
