package com.cobre.notifications.domain.exception;

/** Los criterios de consulta recibidos no son coherentes. */
public class InvalidQueryException extends RuntimeException {

    public InvalidQueryException(String message) {
        super(message);
    }
}
