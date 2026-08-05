package com.cobre.notifications.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mensaje que circula por la cola de entrega y por las de reintento.
 *
 * <p>Lleva el identificador del evento y su tenant, nada mas. El estado autoritativo
 * vive en la base de datos, de modo que un mensaje que estuvo media hora esperando en
 * una cola de retardo no puede revertir un estado mas reciente: al procesarlo se
 * relee el evento.
 *
 * <p>El {@code client_id} viaja para que el consumidor pueda etiquetar sus logs por
 * cliente antes de tocar la base, que es lo que permite responder "que paso con las
 * notificaciones de este cliente" con una sola consulta en Kibana.
 */
public record DeliveryCommandMessage(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("client_id") String clientId) {
}
