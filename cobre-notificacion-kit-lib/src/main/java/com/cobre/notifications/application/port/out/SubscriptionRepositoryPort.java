package com.cobre.notifications.application.port.out;

import com.cobre.notifications.domain.model.Subscription;
import reactor.core.publisher.Flux;
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

    /** Suscripciones activas del cliente, para el listado self-service. */
    Flux<Subscription> findAllActiveByClientId(String clientId);

    /**
     * Da de alta la suscripcion o reemplaza la que ya existia para ese tipo de evento.
     *
     * <p>Reemplazar y no rechazar: cambiar la URL de un webhook es una operacion normal
     * —el cliente migra de dominio— y obligarlo a borrar y crear lo dejaria sin recibir
     * nada en el intervalo.
     */
    Mono<Subscription> save(Subscription subscription);

    /** @return {@code true} si habia una suscripcion activa que desactivar */
    Mono<Boolean> deactivate(String clientId, String eventType);
}
