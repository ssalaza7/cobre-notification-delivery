package com.cobre.notifications.application.service;

import com.cobre.notifications.application.port.in.GetNotificationEventUseCase;
import com.cobre.notifications.application.port.out.DeliveryAttemptRepositoryPort;
import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.domain.exception.NotificationEventNotFoundException;
import com.cobre.notifications.domain.model.NotificationEventDetail;
import reactor.core.publisher.Mono;

/**
 * Detalle de una notificacion con su bitacora de intentos.
 *
 * <p>La busqueda va siempre por (id, cliente). Un evento de otro tenant es
 * indistinguible de uno inexistente: ambos terminan en 404.
 */
public class GetNotificationEventService implements GetNotificationEventUseCase {

    private final NotificationEventRepositoryPort events;
    private final DeliveryAttemptRepositoryPort attempts;

    public GetNotificationEventService(
            NotificationEventRepositoryPort events, DeliveryAttemptRepositoryPort attempts) {
        this.events = events;
        this.attempts = attempts;
    }

    @Override
    public Mono<NotificationEventDetail> get(String eventId, String clientId) {
        return events.findByIdAndClientId(eventId, clientId)
                .switchIfEmpty(Mono.error(new NotificationEventNotFoundException(eventId)))
                .flatMap(event -> attempts.findByEventId(eventId)
                        .collectList()
                        .map(list -> new NotificationEventDetail(event, list)));
    }
}
