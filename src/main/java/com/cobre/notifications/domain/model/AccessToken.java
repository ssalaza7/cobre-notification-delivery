package com.cobre.notifications.domain.model;

import java.time.Duration;
import java.util.Objects;

/**
 * Token de acceso emitido a un cliente.
 *
 * <p>La vigencia es corta a proposito. Un token filtrado —en un log, en un proxy, en
 * una captura— deja de servir en minutos, y ese es el unico control real que existe
 * una vez el token salio del servidor: no hay forma de revocar un JWT ya emitido sin
 * montar una lista de revocacion, que es justo la complejidad que la vida corta evita.
 */
public record AccessToken(String value, Duration expiresIn, String scope) {

    public AccessToken {
        Objects.requireNonNull(value, "value es obligatorio");
        Objects.requireNonNull(expiresIn, "expiresIn es obligatorio");
        if (expiresIn.isNegative() || expiresIn.isZero()) {
            throw new IllegalArgumentException("expiresIn debe ser positivo");
        }
    }

    /** Segundos de vigencia, como los espera la respuesta estandar de OAuth2. */
    public long expiresInSeconds() {
        return expiresIn.toSeconds();
    }
}
