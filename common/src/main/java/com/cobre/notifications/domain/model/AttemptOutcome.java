package com.cobre.notifications.domain.model;

/**
 * Resultado de un intento individual de entrega.
 *
 * <p>La distincion entre fallo reintentable y permanente es la que decide toda la
 * estrategia de reintentos: reintentar un 400 o un 404 del cliente es gastar
 * capacidad sin ninguna posibilidad de exito, mientras que no reintentar un 503
 * pierde una notificacion por una caida momentanea del destino.
 */
public enum AttemptOutcome {

    /** El webhook respondio 2xx. */
    DELIVERED,

    /** Fallo transitorio: 5xx, 408, 429, timeout o error de conexion. Se reintenta. */
    RETRYABLE_FAILURE,

    /** Fallo del contrato: 4xx distinto de 408/429. No se reintenta. */
    PERMANENT_FAILURE;

    public boolean isFailure() {
        return this != DELIVERED;
    }
}
