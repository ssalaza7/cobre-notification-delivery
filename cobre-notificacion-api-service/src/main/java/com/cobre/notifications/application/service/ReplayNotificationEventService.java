package com.cobre.notifications.application.service;

import com.cobre.notifications.application.port.in.ReplayNotificationEventUseCase;
import com.cobre.notifications.application.port.out.DeliveryQueuePort;
import com.cobre.notifications.application.port.out.MetricsPort;
import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.domain.exception.NotificationEventNotFoundException;
import com.cobre.notifications.domain.model.NotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;

/**
 * Reenvio manual de una notificacion cuya entrega fallo definitivamente.
 *
 * <p>No entrega de forma sincrona. Reabre el ciclo de entrega, lo encola y responde;
 * si entregara en linea, la peticion del cliente quedaria atada al tiempo de
 * respuesta de su propio webhook y perderia toda la resiliencia del flujo normal
 * (reintentos con backoff, DLQ, bitacora).
 *
 * <p>La validacion de que el estado permite reenviar vive en el agregado
 * ({@link NotificationEvent#preparedForReplay}), no aqui: es una regla del dominio.
 */
public class ReplayNotificationEventService implements ReplayNotificationEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReplayNotificationEventService.class);

    private final NotificationEventRepositoryPort events;
    private final DeliveryQueuePort deliveryQueue;
    private final MetricsPort metrics;
    private final Clock clock;

    public ReplayNotificationEventService(
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
    public Mono<NotificationEvent> replay(String eventId, String clientId) {
        return events.findByIdAndClientId(eventId, clientId)
                .switchIfEmpty(Mono.error(new NotificationEventNotFoundException(eventId)))
                .flatMap(this::reopen);
    }

    private Mono<NotificationEvent> reopen(NotificationEvent event) {
        NotificationEvent reopened = event.preparedForReplay(clock.instant());
        return events.update(event, reopened)
                // Vacio significa que otra peticion de reenvio gano la carrera; el
                // evento ya quedo encolado por ella, asi que no se encola dos veces.
                .switchIfEmpty(Mono.error(new NotificationEventNotFoundException(event.eventId())))
                .flatMap(saved -> deliveryQueue.enqueue(saved.eventId(), saved.clientId())
                        .doOnSuccess(ignored -> {
                            metrics.replayRequested(saved.eventType());
                            log.info("Reenvio {} solicitado para la notificacion {} del cliente {}",
                                    saved.replayCount(), saved.eventId(), saved.clientId());
                        })
                        .thenReturn(saved));
    }
}
