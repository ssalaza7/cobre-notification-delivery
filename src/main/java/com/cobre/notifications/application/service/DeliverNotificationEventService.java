package com.cobre.notifications.application.service;

import com.cobre.notifications.application.port.in.DeliverNotificationEventUseCase;
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
import com.cobre.notifications.domain.model.EventVersion;
import com.cobre.notifications.domain.model.NotificationEvent;
import com.cobre.notifications.domain.model.RetryPolicy;
import com.cobre.notifications.domain.model.Subscription;
import com.cobre.notifications.domain.model.WebhookDeliveryRequest;
import com.cobre.notifications.domain.model.WebhookDeliveryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Caso de uso central: intenta entregar una notificacion y decide que hacer con el
 * resultado.
 *
 * <p>El orden de las operaciones es deliberado. Primero se registra el intento en la
 * bitacora, despues se actualiza el estado del evento y solo al final se encola el
 * reintento. Asi, si el proceso muere a mitad de camino, lo peor que pasa es que el
 * broker reentregue el mensaje y se repita un intento —que la version optimista
 * detecta— en vez de perder el rastro de una entrega que si ocurrio.
 */
public class DeliverNotificationEventService implements DeliverNotificationEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeliverNotificationEventService.class);

    private static final String NO_SUBSCRIPTION_REASON =
            "El cliente no tiene una suscripcion activa para este tipo de evento";

    private final NotificationEventRepositoryPort events;
    private final DeliveryAttemptRepositoryPort attempts;
    private final SubscriptionRepositoryPort subscriptions;
    private final WebhookClientPort webhookClient;
    private final DeliveryQueuePort deliveryQueue;
    private final MetricsPort metrics;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final RandomGenerator random;

    public DeliverNotificationEventService(
            NotificationEventRepositoryPort events,
            DeliveryAttemptRepositoryPort attempts,
            SubscriptionRepositoryPort subscriptions,
            WebhookClientPort webhookClient,
            DeliveryQueuePort deliveryQueue,
            MetricsPort metrics,
            RetryPolicy retryPolicy,
            Clock clock,
            RandomGenerator random) {
        this.events = events;
        this.attempts = attempts;
        this.subscriptions = subscriptions;
        this.webhookClient = webhookClient;
        this.deliveryQueue = deliveryQueue;
        this.metrics = metrics;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.random = random;
    }

    @Override
    public Mono<DeliveryOutcome> deliver(String eventId) {
        return events.findById(eventId)
                .switchIfEmpty(Mono.error(new NotificationEventNotFoundException(eventId)))
                .flatMap(this::deliverEvent);
    }

    private Mono<DeliveryOutcome> deliverEvent(NotificationEvent event) {
        // Un mensaje reentregado por el broker puede llegar cuando la entrega ya se
        // cerro. Volver a invocar el webhook duplicaria la notificacion al cliente.
        if (event.deliveryStatus().isTerminal()) {
            log.debug("Evento {} ya esta en estado terminal {}; no se reintenta",
                    event.eventId(), event.deliveryStatus());
            return Mono.just(DeliveryOutcome.ALREADY_SETTLED);
        }
        return subscriptions.findActiveFor(event.clientId(), event.eventType())
                .flatMap(subscription -> attemptDelivery(event, subscription))
                .switchIfEmpty(Mono.defer(() -> discard(event)));
    }

    private Mono<DeliveryOutcome> attemptDelivery(NotificationEvent event, Subscription subscription) {
        NotificationEvent targeted = event.withWebhookUrl(subscription.webhookUrl());
        int attemptNumber = targeted.nextAttemptNumber();

        return webhookClient.deliver(WebhookDeliveryRequest.of(targeted, subscription, attemptNumber))
                .flatMap(result -> recordAttempt(targeted, attemptNumber, result));
    }

    private Mono<DeliveryOutcome> recordAttempt(
            NotificationEvent event, int attemptNumber, WebhookDeliveryResult result) {
        Instant now = clock.instant();
        metrics.deliveryAttempted(event.eventType(), result.outcome(), result.durationMs(), result.httpStatus());

        DeliveryAttempt attempt = DeliveryAttempt.of(
                event.eventId(), attemptNumber, event.replayCount(), now, result);

        return attempts.append(attempt)
                .then(Mono.defer(() -> settle(event, result, now)));
    }

    private Mono<DeliveryOutcome> settle(NotificationEvent event, WebhookDeliveryResult result, Instant now) {
        if (result.isDelivered()) {
            return complete(event, result, now);
        }
        // Un 4xx del cliente no mejora reintentando: el payload o la ruta estan mal.
        // Insistir solo gasta capacidad y ensucia las metricas del destino.
        if (result.outcome() == AttemptOutcome.PERMANENT_FAILURE) {
            return failDefinitively(event, result, now, "fallo permanente del destino");
        }
        Optional<Duration> delay = retryPolicy.nextDelay(event.nextAttemptNumber(), random);
        return delay
                .map(d -> scheduleRetry(event, result, now, d))
                .orElseGet(() -> failDefinitively(event, result, now, "reintentos agotados"));
    }

    private Mono<DeliveryOutcome> complete(NotificationEvent event, WebhookDeliveryResult result, Instant now) {
        EventVersion expected = event.version();
        return events.update(event.markDelivered(result, now), expected)
                .doOnNext(saved -> {
                    metrics.deliverySettled(saved.eventType(), saved.deliveryStatus(), saved.attempts() > 1);
                    log.info("Notificacion {} entregada al cliente {} en el intento {}",
                            saved.eventId(), saved.clientId(), saved.attempts());
                })
                .thenReturn(DeliveryOutcome.DELIVERED)
                .defaultIfEmpty(DeliveryOutcome.ALREADY_SETTLED);
    }

    private Mono<DeliveryOutcome> scheduleRetry(
            NotificationEvent event, WebhookDeliveryResult result, Instant now, Duration delay) {
        EventVersion expected = event.version();
        return events.update(event.markRetrying(result, now), expected)
                .flatMap(saved -> deliveryQueue.enqueueRetry(saved.eventId(), saved.clientId(), delay)
                        .doOnSuccess(ignored -> {
                            metrics.retryScheduled(saved.eventType(), saved.attempts());
                            log.warn("Entrega de {} fallo (intento {}, status {}): reintento en {}",
                                    saved.eventId(), saved.attempts(), result.httpStatus(), delay);
                        })
                        .thenReturn(DeliveryOutcome.RETRY_SCHEDULED))
                .defaultIfEmpty(DeliveryOutcome.ALREADY_SETTLED);
    }

    private Mono<DeliveryOutcome> failDefinitively(
            NotificationEvent event, WebhookDeliveryResult result, Instant now, String reason) {
        EventVersion expected = event.version();
        return events.update(event.markFailed(result, now), expected)
                .flatMap(saved -> deliveryQueue.sendToDeadLetter(saved.eventId(), saved.clientId(), reason)
                        .doOnSuccess(ignored -> {
                            metrics.deliverySettled(saved.eventType(), saved.deliveryStatus(), saved.attempts() > 1);
                            log.error("Entrega de {} para el cliente {} fallo definitivamente ({}) "
                                            + "tras {} intentos; queda disponible para reenvio manual",
                                    saved.eventId(), saved.clientId(), reason, saved.attempts());
                        })
                        .thenReturn(DeliveryOutcome.FAILED))
                .defaultIfEmpty(DeliveryOutcome.ALREADY_SETTLED);
    }

    private Mono<DeliveryOutcome> discard(NotificationEvent event) {
        Instant now = clock.instant();
        return events.update(event.markDiscarded(NO_SUBSCRIPTION_REASON, now), event.version())
                .doOnNext(saved -> {
                    metrics.deliverySettled(saved.eventType(), saved.deliveryStatus(), false);
                    log.info("Evento {} descartado: el cliente {} no tiene suscripcion activa para {}",
                            saved.eventId(), saved.clientId(), saved.eventType());
                })
                .thenReturn(DeliveryOutcome.NOT_SUBSCRIBED)
                .defaultIfEmpty(DeliveryOutcome.ALREADY_SETTLED);
    }
}
