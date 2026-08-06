package com.cobre.notifications.infrastructure.adapter.in.messaging;

import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase.IngestCommand;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Contrato del evento que publica la plataforma en su bus.
 *
 * <p>Los nombres son los del archivo {@code notification_events.json} entregado con
 * la prueba, para que el consumidor hable el mismo lenguaje que el resto de la
 * plataforma.
 */
public record PlatformEventMessage(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("content") String content,
        @JsonProperty("created_at") Instant createdAt) {

    public IngestCommand toCommand() {
        return new IngestCommand(eventId, clientId, eventType, content, createdAt);
    }
}
