package com.cobre.notifications.application.service;

import com.cobre.notifications.application.port.out.DeliveryAttemptRepositoryPort;
import com.cobre.notifications.application.port.out.DeliveryQueuePort;
import com.cobre.notifications.application.port.out.MetricsPort;
import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.application.port.out.SubscriptionRepositoryPort;
import com.cobre.notifications.application.port.out.WebhookClientPort;
import com.cobre.notifications.domain.exception.NotificationEventNotFoundException;
import com.cobre.notifications.domain.model.AttemptOutcome;
import com.cobre.notifications.domain.model.DeliveryAttempt;
import com.cobre.notifications.domain.model.DeliveryOutcome;
import com.cobre.notifications.domain.model.DeliveryStatus;
import com.cobre.notifications.domain.model.NotificationEvent;
import com.cobre.notifications.domain.model.RetryPolicy;
import com.cobre.notifications.domain.model.Subscription;
import com.cobre.notifications.domain.model.WebhookDeliveryRequest;
import com.cobre.notifications.domain.model.WebhookDeliveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliverNotificationEventServiceTest {

    private static final String EVENT_ID = "EVT001";
    private static final String CLIENT_ID = "CLIENT001";
    private static final String EVENT_TYPE = "credit_transfer";
    private static final Instant NOW = Instant.parse("2024-03-15T12:00:00Z");
    private static final Duration FIRST_STEP = Duration.ofSeconds(5);

    private final NotificationEventRepositoryPort events = mock(NotificationEventRepositoryPort.class);
    private final DeliveryAttemptRepositoryPort attempts = mock(DeliveryAttemptRepositoryPort.class);
    private final SubscriptionRepositoryPort subscriptions = mock(SubscriptionRepositoryPort.class);
    private final WebhookClientPort webhookClient = mock(WebhookClientPort.class);
    private final DeliveryQueuePort deliveryQueue = mock(DeliveryQueuePort.class);
    private final MetricsPort metrics = mock(MetricsPort.class);

    private final RetryPolicy retryPolicy =
            new RetryPolicy(3, List.of(FIRST_STEP, Duration.ofSeconds(30)), 0);
    private final RandomGenerator noJitter = () -> 0L;

    private DeliverNotificationEventService service;

    @BeforeEach
    void setUp() {
        service = new DeliverNotificationEventService(
                events, attempts, subscriptions, webhookClient, deliveryQueue,
                metrics, retryPolicy, Clock.fixed(NOW, ZoneOffset.UTC), noJitter);

        when(attempts.append(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(events.update(any(), any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(deliveryQueue.enqueueRetry(anyString(), anyString(), any())).thenReturn(Mono.empty());
        when(deliveryQueue.sendToDeadLetter(anyString(), anyString(), anyString())).thenReturn(Mono.empty());
    }

    private NotificationEvent pendingEvent() {
        return NotificationEvent.received(
                EVENT_ID, CLIENT_ID, EVENT_TYPE, "Transferencia recibida",
                NOW.minusSeconds(2), NOW.minusSeconds(2));
    }

    private Subscription subscription() {
        return new Subscription(
                UUID.randomUUID(), CLIENT_ID, "*",
                "https://cliente.example.com/hook", "whsec_test", true);
    }

    private void givenEvent(NotificationEvent event) {
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(event));
    }

    private void givenActiveSubscription() {
        when(subscriptions.findActiveFor(CLIENT_ID, EVENT_TYPE)).thenReturn(Mono.just(subscription()));
    }

    private void givenWebhookResponds(WebhookDeliveryResult result) {
        when(webhookClient.deliver(any(WebhookDeliveryRequest.class))).thenReturn(Mono.just(result));
    }

    @Test
    @DisplayName("una respuesta 2xx cierra la notificacion como entregada")
    void entrega_exitosa() {
        givenEvent(pendingEvent());
        givenActiveSubscription();
        givenWebhookResponds(WebhookDeliveryResult.delivered(200, 120));

        StepVerifier.create(service.deliver(EVENT_ID))
                .expectNext(DeliveryOutcome.DELIVERED)
                .verifyComplete();

        ArgumentCaptor<NotificationEvent> saved = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(events).update(saved.capture(), any());
        assertThat(saved.getValue().deliveryStatus()).isEqualTo(DeliveryStatus.COMPLETED);
        assertThat(saved.getValue().attempts()).isEqualTo(1);
        assertThat(saved.getValue().webhookUrl()).isEqualTo("https://cliente.example.com/hook");

        verify(metrics).deliverySettled(EVENT_TYPE, DeliveryStatus.COMPLETED, false);
        verify(deliveryQueue, never()).enqueueRetry(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("el intento queda registrado en la bitacora aunque la entrega falle")
    void registra_el_intento() {
        givenEvent(pendingEvent());
        givenActiveSubscription();
        givenWebhookResponds(WebhookDeliveryResult.retryable(503, "servicio no disponible", 5000));

        StepVerifier.create(service.deliver(EVENT_ID))
                .expectNext(DeliveryOutcome.RETRY_SCHEDULED)
                .verifyComplete();

        ArgumentCaptor<DeliveryAttempt> attempt = ArgumentCaptor.forClass(DeliveryAttempt.class);
        verify(attempts).append(attempt.capture());
        assertThat(attempt.getValue().attemptNumber()).isEqualTo(1);
        assertThat(attempt.getValue().outcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
        assertThat(attempt.getValue().httpStatus()).isEqualTo(503);
        verify(metrics).deliveryAttempted(EVENT_TYPE, AttemptOutcome.RETRYABLE_FAILURE, 5000, 503);
    }

    @Test
    @DisplayName("un fallo transitorio programa el reintento con el primer escalon de espera")
    void programa_reintento() {
        givenEvent(pendingEvent());
        givenActiveSubscription();
        givenWebhookResponds(WebhookDeliveryResult.retryable(500, "error interno", 3000));

        StepVerifier.create(service.deliver(EVENT_ID))
                .expectNext(DeliveryOutcome.RETRY_SCHEDULED)
                .verifyComplete();

        verify(deliveryQueue).enqueueRetry(EVENT_ID, CLIENT_ID, FIRST_STEP);
        verify(metrics).retryScheduled(EVENT_TYPE, 1);

        ArgumentCaptor<NotificationEvent> saved = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(events).update(saved.capture(), any());
        assertThat(saved.getValue().deliveryStatus()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(saved.getValue().deliveryDate()).isNull();
    }

    @Test
    @DisplayName("agotados los reintentos, la notificacion queda fallida y va a la cola muerta")
    void agota_los_reintentos() {
        NotificationEvent almostExhausted = pendingEvent()
                .markRetrying(WebhookDeliveryResult.retryable(503, "e", 10), NOW)
                .markRetrying(WebhookDeliveryResult.retryable(503, "e", 10), NOW);
        givenEvent(almostExhausted);
        givenActiveSubscription();
        givenWebhookResponds(WebhookDeliveryResult.retryable(503, "sigue caido", 5000));

        StepVerifier.create(service.deliver(EVENT_ID))
                .expectNext(DeliveryOutcome.FAILED)
                .verifyComplete();

        ArgumentCaptor<NotificationEvent> saved = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(events).update(saved.capture(), any());
        assertThat(saved.getValue().deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(saved.getValue().attempts()).isEqualTo(3);
        assertThat(saved.getValue().deliveryStatus().isReplayable()).isTrue();

        verify(deliveryQueue).sendToDeadLetter(eq(EVENT_ID), eq(CLIENT_ID), anyString());
        verify(deliveryQueue, never()).enqueueRetry(anyString(), anyString(), any());
        verify(metrics).deliverySettled(EVENT_TYPE, DeliveryStatus.FAILED, true);
    }

    @Test
    @DisplayName("un fallo permanente no se reintenta aunque queden intentos disponibles")
    void no_reintenta_fallos_permanentes() {
        givenEvent(pendingEvent());
        givenActiveSubscription();
        givenWebhookResponds(WebhookDeliveryResult.permanent(400, "payload rechazado", 80));

        StepVerifier.create(service.deliver(EVENT_ID))
                .expectNext(DeliveryOutcome.FAILED)
                .verifyComplete();

        verify(deliveryQueue, never()).enqueueRetry(anyString(), anyString(), any());
        verify(deliveryQueue).sendToDeadLetter(eq(EVENT_ID), eq(CLIENT_ID), anyString());
    }

    @Test
    @DisplayName("sin suscripcion activa no se invoca ningun webhook y el evento se descarta")
    void descarta_sin_suscripcion() {
        givenEvent(pendingEvent());
        when(subscriptions.findActiveFor(CLIENT_ID, EVENT_TYPE)).thenReturn(Mono.empty());

        StepVerifier.create(service.deliver(EVENT_ID))
                .expectNext(DeliveryOutcome.NOT_SUBSCRIBED)
                .verifyComplete();

        verify(webhookClient, never()).deliver(any());
        verify(attempts, never()).append(any());

        ArgumentCaptor<NotificationEvent> saved = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(events).update(saved.capture(), any());
        assertThat(saved.getValue().deliveryStatus()).isEqualTo(DeliveryStatus.DISCARDED);
        assertThat(saved.getValue().attempts()).isZero();
    }

    @Test
    @DisplayName("una reentrega del broker sobre un evento ya cerrado no vuelve a notificar al cliente")
    void ignora_eventos_ya_cerrados() {
        givenEvent(pendingEvent().markDelivered(WebhookDeliveryResult.delivered(200, 10), NOW));

        StepVerifier.create(service.deliver(EVENT_ID))
                .expectNext(DeliveryOutcome.ALREADY_SETTLED)
                .verifyComplete();

        verify(webhookClient, never()).deliver(any());
        verify(events, never()).update(any(), any());
    }

    @Test
    @DisplayName("si otra instancia gano la carrera, no se encola un reintento duplicado")
    void detecta_conflicto_de_version() {
        givenEvent(pendingEvent());
        givenActiveSubscription();
        givenWebhookResponds(WebhookDeliveryResult.retryable(503, "no disponible", 5000));
        // doReturn y no when(...): re-stubbear con when invocaria el answer anterior.
        doReturn(Mono.empty()).when(events).update(any(), any());

        StepVerifier.create(service.deliver(EVENT_ID))
                .expectNext(DeliveryOutcome.ALREADY_SETTLED)
                .verifyComplete();

        verify(deliveryQueue, never()).enqueueRetry(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("una orden de entrega para un evento inexistente falla explicitamente")
    void falla_si_el_evento_no_existe() {
        when(events.findById(EVENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.deliver(EVENT_ID))
                .expectError(NotificationEventNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("un reenvio conserva su numero de ciclo en la bitacora de intentos")
    void conserva_el_ciclo_de_reenvio() {
        NotificationEvent replayed = pendingEvent()
                .markFailed(WebhookDeliveryResult.retryable(503, "e", 10), NOW)
                .preparedForReplay(NOW);
        givenEvent(replayed);
        givenActiveSubscription();
        givenWebhookResponds(WebhookDeliveryResult.delivered(200, 90));

        StepVerifier.create(service.deliver(EVENT_ID))
                .expectNext(DeliveryOutcome.DELIVERED)
                .verifyComplete();

        ArgumentCaptor<DeliveryAttempt> attempt = ArgumentCaptor.forClass(DeliveryAttempt.class);
        verify(attempts).append(attempt.capture());
        assertThat(attempt.getValue().replayCount()).isEqualTo(1);
        assertThat(attempt.getValue().attemptNumber()).isEqualTo(1);
    }
}
