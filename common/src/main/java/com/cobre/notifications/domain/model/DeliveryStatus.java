package com.cobre.notifications.domain.model;

import java.util.Locale;

/**
 * Ciclo de vida de la entrega de una notificacion.
 *
 * <p>El archivo {@code notification_events.json} solo contempla {@code completed} y
 * {@code failed}; son los dos estados terminales. Los intermedios existen porque la
 * entrega es asincrona y la API self-service debe poder mostrar el estado real en
 * curso, no solo el desenlace.
 */
public enum DeliveryStatus {

    /** Recibido y persistido, aun sin ningun intento de entrega. */
    PENDING,

    /** Al menos un intento fallo de forma transitoria; hay un reintento programado. */
    RETRYING,

    /** El webhook respondio 2xx. Estado terminal. */
    COMPLETED,

    /** Se agotaron los reintentos o hubo un fallo permanente. Estado terminal y reenviable. */
    FAILED,

    /** El cliente no tiene suscripcion activa para este tipo de evento. Estado terminal, no reenviable. */
    DISCARDED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == DISCARDED;
    }

    /**
     * Solo un fallo definitivo se puede reenviar. Reenviar algo que ya esta entregado
     * duplicaria la notificacion al cliente, y reenviar algo en curso competiria con
     * el reintento que ya esta programado en el broker.
     */
    public boolean isReplayable() {
        return this == FAILED;
    }

    /** Nombre en minuscula, que es como lo expone la API publica y como venia en el JSON de origen. */
    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Acepta el valor tal como llega de la API (minuscula) o el nombre del enum. */
    public static DeliveryStatus fromApiValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return DeliveryStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
