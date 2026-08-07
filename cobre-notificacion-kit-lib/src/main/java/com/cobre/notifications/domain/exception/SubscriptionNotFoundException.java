package com.cobre.notifications.domain.exception;

/** No hay una suscripcion activa del cliente para ese tipo de evento. */
public class SubscriptionNotFoundException extends RuntimeException {

    public SubscriptionNotFoundException(String eventType) {
        super("No hay una suscripcion activa para el tipo de evento '" + eventType + "'");
    }
}
