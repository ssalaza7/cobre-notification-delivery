package com.cobre.notifications.application.port.in;

import com.cobre.notifications.domain.model.DeliveryOutcome;
import reactor.core.publisher.Mono;

/**
 * Puerto de entrada: ejecutar un intento de entrega de una notificacion ya
 * persistida.
 *
 * <p>Lo invocan tanto el consumidor de la cola de entrega como el de las colas de
 * reintento. Recibe solo el identificador: el estado autoritativo esta en la base
 * de datos, no en el mensaje, de modo que un mensaje viejo reencolado nunca
 * revierte un estado mas nuevo.
 */
public interface DeliverNotificationEventUseCase {

    Mono<DeliveryOutcome> deliver(String eventId);
}
