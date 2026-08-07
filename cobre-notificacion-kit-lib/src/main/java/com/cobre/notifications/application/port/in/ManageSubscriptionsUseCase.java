package com.cobre.notifications.application.port.in;

import com.cobre.notifications.domain.model.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Registro self-service de webhooks.
 *
 * <p>Un cliente puede tener varios: uno por tipo de evento, mas un comodin que recoge el
 * resto. Permite mandar las transferencias a un sistema y las alertas de saldo a otro sin
 * duplicar configuracion.
 */
public interface ManageSubscriptionsUseCase {

    /**
     * Registra el webhook, o actualiza el que ya existia para ese tipo de evento.
     *
     * <p>Devuelve la suscripcion con su secreto de firma. Es la unica vez que se entrega:
     * despues solo se puede rotar, no consultar.
     */
    Mono<Subscription> register(String clientId, String eventType, String webhookUrl);

    Flux<Subscription> list(String clientId);

    /** Deja de entregar sin borrar el historial. */
    Mono<Void> deactivate(String clientId, String eventType);
}
