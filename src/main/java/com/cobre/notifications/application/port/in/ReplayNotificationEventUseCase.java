package com.cobre.notifications.application.port.in;

import com.cobre.notifications.domain.model.NotificationEvent;
import reactor.core.publisher.Mono;

/** Puerto de entrada: reenvio manual de una notificacion cuya entrega fallo. */
public interface ReplayNotificationEventUseCase {

    /**
     * Abre un ciclo de entrega nuevo y lo encola. No entrega de forma sincrona: la
     * respuesta de la API es 202 y el resultado se consulta despues por el endpoint
     * de detalle.
     *
     * @param clientId tenant dueno del recurso, tomado del token; nunca de la peticion
     */
    Mono<NotificationEvent> replay(String eventId, String clientId);
}
