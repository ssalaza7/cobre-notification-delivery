package com.cobre.notifications.application.service;

import com.cobre.notifications.application.port.in.QueryNotificationEventsUseCase;
import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.domain.model.EventQuery;
import com.cobre.notifications.domain.model.NotificationEvent;
import com.cobre.notifications.domain.model.PageResult;
import reactor.core.publisher.Mono;

/**
 * Listado paginado de notificaciones del cliente autenticado.
 *
 * <p>La pagina y el total se resuelven en paralelo: son dos consultas independientes
 * y encadenarlas duplicaria la latencia sin ganar nada.
 */
public class QueryNotificationEventsService implements QueryNotificationEventsUseCase {

    private final NotificationEventRepositoryPort events;

    public QueryNotificationEventsService(NotificationEventRepositoryPort events) {
        this.events = events;
    }

    @Override
    public Mono<PageResult<NotificationEvent>> query(EventQuery query) {
        return Mono.zip(events.search(query).collectList(), events.count(query))
                .map(tuple -> new PageResult<>(tuple.getT1(), query.page(), query.size(), tuple.getT2()));
    }
}
