package com.cobre.notifications.infrastructure.adapter.in.web.dto;

import com.cobre.notifications.domain.model.Subscription;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta de un webhook.
 *
 * <p>No lleva {@code client_id}: sale del token. Tampoco lleva el secreto de firma, que
 * lo genera el servicio.
 */
public record SubscriptionRequest(
        @JsonProperty("webhook_url")
        @NotBlank(message = "webhook_url es obligatorio")
        @Size(max = 2048, message = "webhook_url no puede superar 2048 caracteres")
        String webhookUrl,

        /** Ausente significa todos los tipos. */
        @JsonProperty("event_type")
        @Size(max = 64, message = "event_type no puede superar 64 caracteres")
        String eventType) {

    public String eventTypeOrWildcard() {
        return eventType == null || eventType.isBlank() ? Subscription.ALL_EVENT_TYPES : eventType;
    }
}
