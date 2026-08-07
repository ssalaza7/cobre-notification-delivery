package com.cobre.notifications.application.port.out;

import com.cobre.notifications.domain.model.AccessToken;
import reactor.core.publisher.Mono;

/**
 * Puerto de salida que obtiene el token.
 *
 * <p>Existe como puerto para que el caso de uso no sepa quien emite. Hoy lo emite un
 * proveedor OIDC externo y este servicio solo reenvia las credenciales presentadas;
 * antes las verificaba contra una tabla propia y firmaba el JWT el mismo. El caso de
 * uso no cambio al hacerlo, que es la prueba de que la frontera estaba bien puesta.
 *
 * <p>Devuelve {@code Mono} y no un valor directo porque emitir dejo de ser un calculo
 * local para ser una llamada de red.
 *
 * @throws com.cobre.notifications.domain.exception.InvalidClientCredentialsException
 *         si el proveedor rechaza las credenciales
 */
public interface AccessTokenIssuerPort {

    Mono<AccessToken> issue(String clientId, String clientSecret);
}
