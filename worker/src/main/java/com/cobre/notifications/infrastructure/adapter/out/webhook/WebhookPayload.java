package com.cobre.notifications.infrastructure.adapter.out.webhook;

import com.cobre.notifications.domain.model.WebhookDeliveryRequest;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Contrato JSON que reciben los clientes en su webhook.
 *
 * <p>Vive en el adaptador, no en el dominio: es un contrato publico con terceros y
 * evoluciona por razones distintas al modelo interno. Si mas adelante se agrega un
 * campo al agregado, ese campo no se filtra a los clientes sin decidirlo aqui.
 *
 * <p>{@code attempt} viaja en el cuerpo para que el receptor pueda distinguir un
 * reintento de una notificacion nueva y aplicar su propia deduplicacion por
 * {@code event_id}.
 */
public record WebhookPayload(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("content") String content,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("attempt") int attempt) {

    public static WebhookPayload from(WebhookDeliveryRequest request) {
        return new WebhookPayload(
                request.event().eventId(),
                request.event().clientId(),
                request.event().eventType(),
                request.event().content(),
                request.event().createdAt(),
                request.attemptNumber());
    }
}
