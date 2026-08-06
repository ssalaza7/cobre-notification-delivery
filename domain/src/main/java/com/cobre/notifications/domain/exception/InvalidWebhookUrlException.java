package com.cobre.notifications.domain.exception;

/**
 * La URL destino no es un destino valido para una entrega saliente.
 *
 * <p>Se lanza cuando la URL no es HTTPS o apunta a la red interna: sin esa
 * validacion, el servicio se convierte en un proxy para alcanzar servicios
 * internos desde fuera (SSRF, OWASP A10).
 */
public class InvalidWebhookUrlException extends RuntimeException {

    public InvalidWebhookUrlException(String message) {
        super(message);
    }
}
