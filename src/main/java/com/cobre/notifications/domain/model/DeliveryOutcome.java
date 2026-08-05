package com.cobre.notifications.domain.model;

/**
 * Desenlace de una ejecucion del caso de uso de entrega. Es lo que el adaptador de
 * entrada necesita para decidir si confirma (ack) el mensaje del broker.
 */
public enum DeliveryOutcome {

    /** Entregado al webhook del cliente. */
    DELIVERED,

    /** Fallo transitorio y ya quedo encolado el reintento con su retardo. */
    RETRY_SCHEDULED,

    /** Reintentos agotados o fallo permanente: el evento queda en FAILED y es reenviable. */
    FAILED,

    /** El cliente no tiene suscripcion activa para este tipo de evento. */
    NOT_SUBSCRIBED,

    /** El evento ya estaba en un estado terminal; no se hace nada (llegada duplicada). */
    ALREADY_SETTLED
}
