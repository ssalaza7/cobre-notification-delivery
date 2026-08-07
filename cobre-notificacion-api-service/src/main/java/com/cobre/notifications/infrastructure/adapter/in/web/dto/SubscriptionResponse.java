package com.cobre.notifications.infrastructure.adapter.in.web.dto;

import com.cobre.notifications.domain.model.Subscription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Suscripcion tal como la ve el cliente.
 *
 * <p>El secreto solo viaja en la respuesta del alta. En el listado va nulo y se omite:
 * devolverlo en cada consulta lo expondria en cada log, cache y captura de pantalla.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubscriptionResponse(
        @JsonProperty("event_type") String eventType,
        @JsonProperty("webhook_url") String webhookUrl,
        @JsonProperty("active") boolean active,
        @JsonProperty("signing_secret") String signingSecret) {

    public static SubscriptionResponse from(Subscription subscription) {
        return new SubscriptionResponse(
                subscription.eventType(), subscription.webhookUrl(), subscription.active(), null);
    }

    public static SubscriptionResponse withSigningSecret(Subscription subscription) {
        return new SubscriptionResponse(
                subscription.eventType(), subscription.webhookUrl(), subscription.active(),
                subscription.signingSecret());
    }
}
