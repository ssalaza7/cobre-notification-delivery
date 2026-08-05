package com.cobre.notifications.domain.exception;

/** El payload del evento no cumple el contrato minimo para poder procesarse. */
public class InvalidNotificationEventException extends RuntimeException {

    public InvalidNotificationEventException(String message) {
        super(message);
    }
}
