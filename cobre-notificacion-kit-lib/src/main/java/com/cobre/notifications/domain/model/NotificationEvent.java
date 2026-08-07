package com.cobre.notifications.domain.model;

import com.cobre.notifications.domain.exception.InvalidNotificationEventException;
import com.cobre.notifications.domain.exception.ReplayNotAllowedException;

import java.time.Instant;

/**
 * Agregado central: una notificacion de evento y el estado de su entrega.
 *
 * <p>Es inmutable. Cada transicion devuelve una instancia nueva, de modo que el
 * estado anterior nunca se pierde por accidente y las transiciones invalidas se
 * rechazan aqui, no en el adaptador de persistencia ni en el controller.
 */
public record NotificationEvent(
        String eventId,
        String clientId,
        String eventType,
        String content,
        Instant createdAt,
        DeliveryStatus deliveryStatus,
        Instant deliveryDate,
        int attempts,
        int replayCount,
        String webhookUrl,
        Integer lastHttpStatus,
        String lastError,
        Instant updatedAt) {

    public NotificationEvent {
        requireText(eventId, "event_id");
        requireText(clientId, "client_id");
        requireText(eventType, "event_type");
        requireText(content, "content");
        if (createdAt == null) {
            throw new InvalidNotificationEventException("created_at es obligatorio");
        }
        if (deliveryStatus == null) {
            throw new InvalidNotificationEventException("delivery_status es obligatorio");
        }
        if (attempts < 0 || replayCount < 0) {
            throw new InvalidNotificationEventException("attempts y replay_count no pueden ser negativos");
        }
        lastError = DeliveryAttempt.truncate(lastError);
    }

    /** Evento recien recibido de la plataforma, aun sin intentos de entrega. */
    public static NotificationEvent received(
            String eventId, String clientId, String eventType, String content, Instant createdAt, Instant now) {
        return new NotificationEvent(
                eventId, clientId, eventType, content, createdAt,
                DeliveryStatus.PENDING, null, 0, 0, null, null, null, now);
    }

    /** Numero que le corresponde al proximo intento dentro del ciclo de entrega actual. */
    public int nextAttemptNumber() {
        return attempts + 1;
    }

    /** Version optimista con la que se leyo este evento. Ver {@link EventVersion}. */
    public EventVersion version() {
        return new EventVersion(attempts, replayCount);
    }

    public boolean belongsTo(String candidateClientId) {
        return clientId.equals(candidateClientId);
    }

    public NotificationEvent withWebhookUrl(String url) {
        return new NotificationEvent(
                eventId, clientId, eventType, content, createdAt, deliveryStatus, deliveryDate,
                attempts, replayCount, url, lastHttpStatus, lastError, updatedAt);
    }

    /** El webhook respondio 2xx: estado terminal exitoso. */
    public NotificationEvent markDelivered(WebhookDeliveryResult result, Instant now) {
        return new NotificationEvent(
                eventId, clientId, eventType, content, createdAt,
                DeliveryStatus.COMPLETED, now, attempts + 1, replayCount, webhookUrl,
                result.httpStatus(), null, now);
    }

    /** Fallo transitorio con reintento ya programado. */
    public NotificationEvent markRetrying(WebhookDeliveryResult result, Instant now) {
        return new NotificationEvent(
                eventId, clientId, eventType, content, createdAt,
                DeliveryStatus.RETRYING, null, attempts + 1, replayCount, webhookUrl,
                result.httpStatus(), result.errorMessage(), now);
    }

    /** Fallo definitivo: reintentos agotados o error permanente del destino. */
    public NotificationEvent markFailed(WebhookDeliveryResult result, Instant now) {
        return new NotificationEvent(
                eventId, clientId, eventType, content, createdAt,
                DeliveryStatus.FAILED, now, attempts + 1, replayCount, webhookUrl,
                result.httpStatus(), result.errorMessage(), now);
    }

    /**
     * El cliente no tiene suscripcion activa para este tipo de evento: no habia nada
     * que entregar. No cuenta como intento porque nunca se invoco ningun destino.
     */
    public NotificationEvent markDiscarded(String reason, Instant now) {
        return new NotificationEvent(
                eventId, clientId, eventType, content, createdAt,
                DeliveryStatus.DISCARDED, now, attempts, replayCount, webhookUrl,
                null, reason, now);
    }

    /**
     * Prepara un reenvio manual: abre un ciclo de entrega nuevo.
     *
     * <p>Se reinicia {@code attempts} para que el backoff empiece de cero (si no, el
     * reenvio arrancaria directamente en el ultimo escalon de espera) y se incrementa
     * {@code replayCount}, que es lo que permite distinguir en la bitacora los intentos
     * de un ciclo y otro sin borrar historia.
     *
     * @throws ReplayNotAllowedException si el evento no esta en un fallo definitivo
     */
    public NotificationEvent preparedForReplay(Instant now) {
        if (!deliveryStatus.isReplayable()) {
            throw new ReplayNotAllowedException(eventId, deliveryStatus);
        }
        return new NotificationEvent(
                eventId, clientId, eventType, content, createdAt,
                DeliveryStatus.PENDING, null, 0, replayCount + 1, webhookUrl,
                null, null, now);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidNotificationEventException(field + " es obligatorio");
        }
    }
}
