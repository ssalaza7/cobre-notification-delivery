package com.cobre.notifications.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Suscripcion de un cliente: es la que responde "este evento debe entregarse?" y
 * "a donde?".
 *
 * <p>Es la pieza que garantiza el requisito de que las notificaciones enviadas a un
 * cliente correspondan a eventos generados por ese mismo cliente: la URL destino
 * nunca viene en el evento ni en la peticion, se deriva del {@code clientId} del
 * evento consultando su suscripcion.
 */
public record Subscription(
        UUID id,
        String clientId,
        String eventType,
        String webhookUrl,
        String signingSecret,
        boolean active) {

    /** Comodin que representa "suscrito a todos los tipos de evento". */
    public static final String ALL_EVENT_TYPES = "*";

    /** Alta de una suscripcion: nace activa y con su propio secreto de firma. */
    public static Subscription register(
            String clientId, String eventType, String webhookUrl, String signingSecret) {
        return new Subscription(
                UUID.randomUUID(), clientId, eventType, webhookUrl, signingSecret, true);
    }

    public Subscription {
        Objects.requireNonNull(id, "id es obligatorio");
        requireText(clientId, "clientId");
        requireText(eventType, "eventType");
        requireText(webhookUrl, "webhookUrl");
        requireText(signingSecret, "signingSecret");
    }

    public boolean covers(String candidateEventType) {
        return ALL_EVENT_TYPES.equals(eventType) || eventType.equals(candidateEventType);
    }

    public boolean deliversTo(String candidateClientId) {
        return active && clientId.equals(candidateClientId);
    }

    /**
     * Copia desactivada. No se borra la fila: la bitacora de lo ya entregado apunta a
     * ella y borrarla dejaria el historial huerfano.
     */
    public Subscription deactivated() {
        return new Subscription(id, clientId, eventType, webhookUrl, signingSecret, false);
    }

    /**
     * Devuelve una copia apuntando a otra URL. Se usa unicamente para la demo en vivo,
     * donde la URL destino se entrega el mismo dia de la presentacion.
     */
    public Subscription withWebhookUrl(String newWebhookUrl) {
        return new Subscription(id, clientId, eventType, newWebhookUrl, signingSecret, active);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
    }
}
