package com.cobre.notifications.domain.model;

import java.util.List;

/**
 * Vista de detalle de una notificacion: el estado actual mas la bitacora completa de
 * intentos.
 *
 * <p>Es lo que necesita el equipo de monitoreo (o el propio cliente) para entender
 * por que una entrega termino como termino, sin tener que pedir logs.
 */
public record NotificationEventDetail(NotificationEvent event, List<DeliveryAttempt> attempts) {

    public NotificationEventDetail {
        attempts = List.copyOf(attempts);
    }
}
