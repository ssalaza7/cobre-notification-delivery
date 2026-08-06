package com.cobre.notifications.infrastructure.adapter.in.web.dto;

import com.cobre.notifications.domain.model.NotificationEvent;
import com.cobre.notifications.domain.model.NotificationEventDetail;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Detalle de una notificacion con su bitacora de intentos.
 *
 * <p>Los campos van planos y no anidados bajo el listado: es un contrato publico y
 * conviene que se lea igual que el elemento del listado, mas informacion.
 */
public record NotificationEventDetailResponse(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("content") String content,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("delivery_status") String deliveryStatus,
        @JsonProperty("delivery_date") Instant deliveryDate,
        @JsonProperty("attempts") int attempts,
        @JsonProperty("replay_count") int replayCount,
        @JsonProperty("webhook_url") String webhookUrl,
        @JsonProperty("last_http_status") Integer lastHttpStatus,
        @JsonProperty("last_error") String lastError,
        @JsonProperty("delivery_attempts") List<DeliveryAttemptResponse> deliveryAttempts) {

    public static NotificationEventDetailResponse from(NotificationEventDetail detail) {
        NotificationEvent event = detail.event();
        return new NotificationEventDetailResponse(
                event.eventId(),
                event.clientId(),
                event.eventType(),
                event.content(),
                event.createdAt(),
                event.deliveryStatus().apiValue(),
                event.deliveryDate(),
                event.attempts(),
                event.replayCount(),
                event.webhookUrl(),
                event.lastHttpStatus(),
                event.lastError(),
                detail.attempts().stream().map(DeliveryAttemptResponse::from).toList());
    }
}
