package com.cobre.notifications.infrastructure.adapter.in.web.dto;

import com.cobre.notifications.application.port.in.IssueAccessTokenUseCase.ClientCredentials;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Peticion de token, con los nombres de campo del flujo {@code client_credentials} de
 * OAuth2.
 *
 * <p>{@code grant_type} se exige aunque hoy solo exista un valor posible: es lo que
 * permite anadir otro flujo manana sin romper a los clientes que ya integraron.
 */
public record TokenRequest(
        @JsonProperty("grant_type")
        @NotBlank(message = "grant_type es obligatorio")
        @Pattern(regexp = "client_credentials", message = "grant_type soportado: client_credentials")
        String grantType,

        @JsonProperty("client_id")
        @NotBlank(message = "client_id es obligatorio")
        String clientId,

        @JsonProperty("client_secret")
        @NotBlank(message = "client_secret es obligatorio")
        String clientSecret) {

    public ClientCredentials toCredentials() {
        return new ClientCredentials(clientId, clientSecret);
    }
}
