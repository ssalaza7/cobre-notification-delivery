package com.cobre.notifications.infrastructure.adapter.in.web;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Extrae el tenant del token.
 *
 * <p>Un token valido pero sin {@code client_id} no identifica a nadie: se rechaza en
 * vez de asumir un valor por defecto, porque cualquier valor asumido seria una via
 * para leer datos ajenos.
 */
final class AuthenticatedClient {

    static final String CLIENT_ID_CLAIM = "client_id";

    private AuthenticatedClient() {
    }

    static String clientIdOf(Jwt jwt) {
        if (jwt == null) {
            throw new InvalidBearerTokenException("La peticion no esta autenticada");
        }
        String clientId = jwt.getClaimAsString(CLIENT_ID_CLAIM);
        if (clientId == null || clientId.isBlank()) {
            throw new InvalidBearerTokenException("El token no contiene el claim '" + CLIENT_ID_CLAIM + "'");
        }
        return clientId;
    }
}
