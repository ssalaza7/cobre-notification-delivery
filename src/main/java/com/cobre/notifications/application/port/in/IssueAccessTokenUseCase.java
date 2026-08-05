package com.cobre.notifications.application.port.in;

import com.cobre.notifications.domain.model.AccessToken;
import reactor.core.publisher.Mono;

/**
 * Puerto de entrada: intercambiar credenciales de cliente por un token de acceso.
 *
 * <p>Es el flujo {@code client_credentials} de OAuth2, el que usan las integraciones
 * entre sistemas: no hay un usuario delante que autorice nada, es una aplicacion
 * probando su identidad ante otra.
 */
public interface IssueAccessTokenUseCase {

    Mono<AccessToken> issue(ClientCredentials credentials);

    /**
     * Credenciales presentadas por el cliente.
     *
     * <p>No incluye scopes: el cliente pide un token, no elige sus permisos. Esos salen
     * de la credencial registrada, no de la peticion.
     */
    record ClientCredentials(String clientId, String clientSecret) {
    }
}
