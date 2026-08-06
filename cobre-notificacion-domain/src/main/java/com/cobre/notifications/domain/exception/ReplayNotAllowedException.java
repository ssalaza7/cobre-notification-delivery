package com.cobre.notifications.domain.exception;

import com.cobre.notifications.domain.model.DeliveryStatus;

/** Solo se puede reenviar una notificacion cuya entrega fallo de forma definitiva. */
public class ReplayNotAllowedException extends RuntimeException {

    private final String eventId;
    private final DeliveryStatus currentStatus;

    public ReplayNotAllowedException(String eventId, DeliveryStatus currentStatus) {
        super("La notificacion " + eventId + " esta en estado " + currentStatus.apiValue()
                + "; solo se puede reenviar una entrega en estado failed");
        this.eventId = eventId;
        this.currentStatus = currentStatus;
    }

    public String eventId() {
        return eventId;
    }

    public DeliveryStatus currentStatus() {
        return currentStatus;
    }
}
