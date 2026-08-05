package com.cobre.notifications.application.port.in;

import com.cobre.notifications.domain.model.NotificationEventDetail;
import reactor.core.publisher.Mono;

/** Puerto de entrada: detalle de una notificacion con su bitacora de intentos. */
public interface GetNotificationEventUseCase {

    /**
     * @param clientId tenant dueno del recurso, tomado del token; nunca de la peticion
     */
    Mono<NotificationEventDetail> get(String eventId, String clientId);
}
