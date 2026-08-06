package com.cobre.notifications.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro inmutable de un intento de entrega. La tabla es append-only: nunca se
 * actualiza ni se borra una fila.
 *
 * <p>Es la fuente de verdad para responder una queja de un cliente ("mi webhook
 * nunca recibio X") con datos y no con suposiciones: cuando se intento, cuantas
 * veces, que respondio el destino y cuanto tardo.
 */
public record DeliveryAttempt(
        UUID id,
        String eventId,
        int attemptNumber,
        int replayCount,
        Instant attemptedAt,
        AttemptOutcome outcome,
        Integer httpStatus,
        long durationMs,
        String errorMessage) {

    /** Tope defensivo: el mensaje de error viene de un tercero y alimenta una columna acotada. */
    public static final int MAX_ERROR_LENGTH = 512;

    public DeliveryAttempt {
        Objects.requireNonNull(id, "id es obligatorio");
        Objects.requireNonNull(eventId, "eventId es obligatorio");
        Objects.requireNonNull(attemptedAt, "attemptedAt es obligatorio");
        Objects.requireNonNull(outcome, "outcome es obligatorio");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber debe ser >= 1");
        }
        errorMessage = truncate(errorMessage);
    }

    public static DeliveryAttempt of(
            String eventId,
            int attemptNumber,
            int replayCount,
            Instant attemptedAt,
            WebhookDeliveryResult result) {
        return new DeliveryAttempt(
                UUID.randomUUID(),
                eventId,
                attemptNumber,
                replayCount,
                attemptedAt,
                result.outcome(),
                result.httpStatus(),
                result.durationMs(),
                result.errorMessage());
    }

    static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
