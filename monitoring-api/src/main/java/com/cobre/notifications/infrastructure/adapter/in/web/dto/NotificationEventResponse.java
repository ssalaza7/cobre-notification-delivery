package com.cobre.notifications.infrastructure.adapter.in.web.dto;

import com.cobre.notifications.domain.model.NotificationEvent;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Representacion publica de una notificacion.
 *
 * <p>Conserva los nombres del archivo {@code notification_events.json} entregado con
 * la prueba y agrega los campos que la operacion necesita para diagnosticar una
 * entrega. Es un DTO propio del adaptador: el agregado puede cambiar sin romper a los
 * clientes de la API.
 */
public record NotificationEventResponse(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("content") String content,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("delivery_status") String deliveryStatus,
        @JsonProperty("delivery_date") Instant deliveryDate,
        @JsonProperty("attempts") int attempts,
        @JsonProperty("replay_count") int replayCount,
        @JsonProperty("last_http_status") Integer lastHttpStatus,
        @JsonProperty("last_error") String lastError) {

    public static NotificationEventResponse from(NotificationEvent event) {
        return new NotificationEventResponse(
                event.eventId(),
                event.clientId(),
                event.eventType(),
                event.content(),
                event.createdAt(),
                event.deliveryStatus().apiValue(),
                event.deliveryDate(),
                event.attempts(),
                event.replayCount(),
                event.lastHttpStatus(),
                event.lastError());
    }
}
