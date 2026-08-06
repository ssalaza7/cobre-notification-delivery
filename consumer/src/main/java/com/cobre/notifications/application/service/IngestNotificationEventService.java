package com.cobre.notifications.application.service;

import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase;
import com.cobre.notifications.application.port.out.DeliveryQueuePort;
import com.cobre.notifications.application.port.out.MetricsPort;
import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.domain.model.NotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Alta de un evento generado por la plataforma.
 *
 * <p>Persiste primero y encola despues: si se encolara primero, un consumidor rapido
 * podria intentar entregar un evento que todavia no existe en base. Y como el
 * {@code event_id} es la clave primaria, una reentrega del mismo mensaje por parte
 * del broker no crea un duplicado ni dispara una segunda entrega.
 */
public class IngestNotificationEventService implements IngestNotificationEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(IngestNotificationEventService.class);

    private final NotificationEventRepositoryPort events;
    private final DeliveryQueuePort deliveryQueue;
    private final MetricsPort metrics;
    private final Clock clock;

    public IngestNotificationEventService(
            NotificationEventRepositoryPort events,
            DeliveryQueuePort deliveryQueue,
            MetricsPort metrics,
            Clock clock) {
        this.events = events;
        this.deliveryQueue = deliveryQueue;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Mono<IngestResult> ingest(IngestCommand command) {
        Instant now = clock.instant();
        Instant createdAt = command.createdAt() != null ? command.createdAt() : now;

        NotificationEvent event = NotificationEvent.received(
                command.eventId(),
                command.clientId(),
                command.eventType(),
                command.content(),
                createdAt,
                now);

        return events.insertIfAbsent(event)
                .flatMap(inserted -> inserted ? accept(event) : recoverIfStranded(event));
    }

    private Mono<IngestResult> accept(NotificationEvent event) {
        return deliveryQueue.enqueue(event.eventId(), event.clientId())
                .doOnSuccess(ignored -> {
                    metrics.eventIngested(event.eventType());
                    log.debug("Evento {} del cliente {} aceptado y encolado para entrega",
                            event.eventId(), event.clientId());
                })
                .thenReturn(IngestResult.ACCEPTED);
    }

    /**
     * El evento ya existia. Casi siempre es una reentrega del broker y no hay nada que
     * hacer, pero hay un caso que si exige actuar: que la fila se escribiera en un
     * intento anterior <b>cuyo encolado fallo</b>. Ese evento quedaria en
     * {@code pending} sin mensaje en la cola, y nadie lo entregaria nunca.
     *
     * <p>Por eso se reencola cuando sigue pendiente. Es seguro hacerlo de mas: el worker
     * relee el estado autoritativo antes de entregar e ignora lo que ya esta cerrado.
     */
    private Mono<IngestResult> recoverIfStranded(NotificationEvent event) {
        return events.findById(event.eventId())
                .filter(existing -> existing.deliveryStatus().isAwaitingFirstDelivery())
                .flatMap(existing -> {
                    log.warn("Evento {} existia sin entrega pendiente en cola; se reencola",
                            existing.eventId());
                    return deliveryQueue.enqueue(existing.eventId(), existing.clientId())
                            .thenReturn(IngestResult.ACCEPTED);
                })
                .defaultIfEmpty(IngestResult.DUPLICATE_IGNORED)
                .doOnNext(result -> {
                    if (result == IngestResult.DUPLICATE_IGNORED) {
                        log.debug("Evento {} ya existia; se ignora la reentrega", event.eventId());
                    }
                });
    }
}
