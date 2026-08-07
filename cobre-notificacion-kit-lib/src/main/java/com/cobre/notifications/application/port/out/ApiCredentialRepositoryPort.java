package com.cobre.notifications.application.port.out;

import com.cobre.notifications.domain.model.ApiCredential;
import reactor.core.publisher.Mono;

/** Puerto de salida hacia el registro de credenciales de API. */
public interface ApiCredentialRepositoryPort {

    /** Credencial activa de un cliente, o vacio si no existe o esta desactivada. */
    Mono<ApiCredential> findActiveByClientId(String clientId);
}
