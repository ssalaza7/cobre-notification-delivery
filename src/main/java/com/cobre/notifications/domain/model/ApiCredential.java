package com.cobre.notifications.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Credencial con la que un cliente obtiene un token de acceso.
 *
 * <p>El secreto **nunca** se guarda en claro: lo que persiste es su hash. Si alguien
 * obtuviera un volcado de la base, no podria suplantar a ningun cliente. Es la misma
 * razon por la que no se almacenan contrasenas en claro, y en una plataforma de pagos
 * la diferencia entre una filtracion y una brecha.
 *
 * <p>Los scopes viven aqui y no en la peticion: el cliente pide un token, no elige sus
 * permisos. Dejar que el solicitante declare su alcance seria una escalada de
 * privilegios de manual.
 */
public record ApiCredential(
        String clientId,
        String secretHash,
        List<String> scopes,
        boolean active) {

    public ApiCredential {
        Objects.requireNonNull(clientId, "clientId es obligatorio");
        Objects.requireNonNull(secretHash, "secretHash es obligatorio");
        scopes = List.copyOf(scopes);
    }

    /** Formato que espera el resource server: scopes separados por espacio. */
    public String scopeClaim() {
        return String.join(" ", scopes);
    }
}
