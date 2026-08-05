package com.cobre.notifications.application.port.out;

import com.cobre.notifications.domain.model.Subscription;
import reactor.core.publisher.Mono;

/** Puerto de salida hacia el registro de suscripciones. */
public interface SubscriptionRepositoryPort {

    /**
     * Suscripcion activa del cliente que cubre ese tipo de evento, o vacio si no la hay.
     *
     * <p>Vacio significa "este evento no debe entregarse": es la confirmacion exigida
     * por el requisito de que un cliente solo reciba eventos propios.
     */
    Mono<Subscription> findActiveFor(String clientId, String eventType);
}
