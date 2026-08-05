package com.cobre.notifications.infrastructure.adapter.in.web.dto;

import com.cobre.notifications.domain.model.AccessToken;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Respuesta estandar de OAuth2 para el flujo {@code client_credentials}. */
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("scope") String scope) {

    public static TokenResponse from(AccessToken token) {
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds(), token.scope());
    }
}
