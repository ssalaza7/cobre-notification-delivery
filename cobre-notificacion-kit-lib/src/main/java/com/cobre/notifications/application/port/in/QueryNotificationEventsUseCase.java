package com.cobre.notifications.application.port.in;

import com.cobre.notifications.domain.model.EventQuery;
import com.cobre.notifications.domain.model.NotificationEvent;
import com.cobre.notifications.domain.model.PageResult;
import reactor.core.publisher.Mono;

/** Puerto de entrada: listado paginado de notificaciones del cliente autenticado. */
public interface QueryNotificationEventsUseCase {

    Mono<PageResult<NotificationEvent>> query(EventQuery query);
}
