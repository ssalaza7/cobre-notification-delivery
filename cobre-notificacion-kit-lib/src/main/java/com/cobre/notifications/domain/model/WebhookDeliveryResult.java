package com.cobre.notifications.domain.model;

import java.util.Objects;

/**
 * Resultado de invocar el webhook del cliente, en terminos del dominio.
 *
 * <p>El adaptador HTTP traduce codigos de estado, timeouts y errores de conexion a
 * este tipo; el caso de uso decide que hacer sin saber nada de HTTP. Cambiar el
 * transporte (de webhook HTTPS a, por ejemplo, una cola del cliente) no toca la
 * logica de reintentos.
 */
public record WebhookDeliveryResult(
        AttemptOutcome outcome,
        Integer httpStatus,
        String errorMessage,
        long durationMs) {

    public WebhookDeliveryResult {
        Objects.requireNonNull(outcome, "outcome es obligatorio");
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs no puede ser negativo");
        }
        errorMessage = DeliveryAttempt.truncate(errorMessage);
    }

    public static WebhookDeliveryResult delivered(int httpStatus, long durationMs) {
        return new WebhookDeliveryResult(AttemptOutcome.DELIVERED, httpStatus, null, durationMs);
    }

    public static WebhookDeliveryResult retryable(Integer httpStatus, String errorMessage, long durationMs) {
        return new WebhookDeliveryResult(AttemptOutcome.RETRYABLE_FAILURE, httpStatus, errorMessage, durationMs);
    }

    public static WebhookDeliveryResult permanent(Integer httpStatus, String errorMessage, long durationMs) {
        return new WebhookDeliveryResult(AttemptOutcome.PERMANENT_FAILURE, httpStatus, errorMessage, durationMs);
    }

    public boolean isDelivered() {
        return outcome == AttemptOutcome.DELIVERED;
    }
}
