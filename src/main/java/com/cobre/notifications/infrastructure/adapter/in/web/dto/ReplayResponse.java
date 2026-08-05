package com.cobre.notifications.infrastructure.adapter.in.web.dto;

import com.cobre.notifications.domain.model.NotificationEvent;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Acuse del reenvio. Se responde 202: el reenvio quedo encolado, no entregado.
 *
 * <p>El resultado se consulta despues con {@code GET /notification_events/{id}}.
 */
public record ReplayResponse(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("delivery_status") String deliveryStatus,
        @JsonProperty("replay_count") int replayCount,
        @JsonProperty("message") String message) {

    public static ReplayResponse from(NotificationEvent event) {
        return new ReplayResponse(
                event.eventId(),
                event.deliveryStatus().apiValue(),
                event.replayCount(),
                "Reenvio encolado; consulte el detalle de la notificacion para ver el resultado");
    }
}
