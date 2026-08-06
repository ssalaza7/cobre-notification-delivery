package com.cobre.notifications.application.port.out;

import com.cobre.notifications.domain.model.DeliveryAttempt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Puerto de salida hacia la bitacora append-only de intentos de entrega. */
public interface DeliveryAttemptRepositoryPort {

    Mono<DeliveryAttempt> append(DeliveryAttempt attempt);

    /** Intentos de un evento, del mas reciente al mas antiguo. */
    Flux<DeliveryAttempt> findByEventId(String eventId);
}
