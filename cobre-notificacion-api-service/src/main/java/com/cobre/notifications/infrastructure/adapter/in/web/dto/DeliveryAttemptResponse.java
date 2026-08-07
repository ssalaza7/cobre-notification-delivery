package com.cobre.notifications.infrastructure.adapter.in.web.dto;

import com.cobre.notifications.domain.model.DeliveryAttempt;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** Un intento de entrega tal como lo ve el cliente en el detalle de la notificacion. */
public record DeliveryAttemptResponse(
        @JsonProperty("attempt_number") int attemptNumber,
        @JsonProperty("replay_count") int replayCount,
        @JsonProperty("attempted_at") Instant attemptedAt,
        @JsonProperty("outcome") String outcome,
        @JsonProperty("http_status") Integer httpStatus,
        @JsonProperty("duration_ms") long durationMs,
        @JsonProperty("error_message") String errorMessage) {

    public static DeliveryAttemptResponse from(DeliveryAttempt attempt) {
        return new DeliveryAttemptResponse(
                attempt.attemptNumber(),
                attempt.replayCount(),
                attempt.attemptedAt(),
                attempt.outcome().name().toLowerCase(java.util.Locale.ROOT),
                attempt.httpStatus(),
                attempt.durationMs(),
                attempt.errorMessage());
    }
}
