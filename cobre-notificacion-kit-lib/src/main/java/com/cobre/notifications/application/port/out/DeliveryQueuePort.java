package com.cobre.notifications.application.port.out;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Puerto de salida hacia la cola de entrega.
 *
 * <p>El reintento se delega al broker en vez de mantenerlo en memoria del proceso:
 * un reintento programado con {@code delayElement} se pierde si la instancia se
 * reinicia o se reescala. En la cola sobrevive al despliegue.
 *
 * <p>Cada operacion recibe tambien el {@code clientId}. No es redundante: el mensaje
 * identifica el trabajo <i>y</i> su tenant, lo que permite al consumidor etiquetar sus
 * logs por cliente sin volver a la base, y deja abierta la puerta a enrutar por
 * cliente cuando uno de ellos genere volumen suficiente para merecer su propia cola.
 */
public interface DeliveryQueuePort {

    /** Encola una entrega para ejecucion inmediata (alta nueva o reenvio manual). */
    Mono<Void> enqueue(String eventId, String clientId);

    /** Encola un reintento que solo debe hacerse visible despues de {@code delay}. */
    Mono<Void> enqueueRetry(String eventId, String clientId, Duration delay);
}
