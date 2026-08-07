package com.cobre.notifications.domain.model;

import java.util.Objects;

/**
 * Todo lo que el adaptador saliente necesita para invocar el webhook: a donde, con
 * que contenido, con que clave firmar y que numero de intento es.
 *
 * <p>El caso de uso arma este objeto sin saber que por debajo hay HTTP.
 */
public record WebhookDeliveryRequest(
        NotificationEvent event,
        String targetUrl,
        String signingSecret,
        int attemptNumber) {

    public WebhookDeliveryRequest {
        Objects.requireNonNull(event, "event es obligatorio");
        Objects.requireNonNull(targetUrl, "targetUrl es obligatorio");
        Objects.requireNonNull(signingSecret, "signingSecret es obligatorio");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber debe ser >= 1");
        }
    }

    public static WebhookDeliveryRequest of(NotificationEvent event, Subscription subscription, int attemptNumber) {
        return new WebhookDeliveryRequest(
                event, subscription.webhookUrl(), subscription.signingSecret(), attemptNumber);
    }
}
