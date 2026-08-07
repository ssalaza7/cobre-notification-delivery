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
 * <p>Una sola consulta: la pagina trae consigo el cursor para continuar, asi que no hay
 * que contar el total aparte. Contarlo obligaria a recorrer todas las notificaciones que
 * cumplen el filtro solo para escribir un numero en la respuesta.
 */
public class QueryNotificationEventsService implements QueryNotificationEventsUseCase {

    private final NotificationEventRepositoryPort events;

    public QueryNotificationEventsService(NotificationEventRepositoryPort events) {
        this.events = events;
    }

    @Override
    public Mono<PageResult<NotificationEvent>> query(EventQuery query) {
        return events.search(query);
    }
}
