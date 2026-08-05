package com.cobre.notifications.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion de seguridad de la API self-service.
 *
 * @param jwt       clave simetrica para validar los tokens
 * @param rateLimit control de abuso por cliente
 */
@ConfigurationProperties(prefix = "cobre.security")
public record SecurityProperties(Jwt jwt, RateLimit rateLimit) {

    /**
     * @param secret clave HS256. Debe tener al menos 32 bytes; en produccion se
     *               inyecta por variable de entorno y lo natural es migrar a
     *               validacion por JWKS contra un proveedor OIDC.
     */
    public record Jwt(String secret) {
    }

    public record RateLimit(boolean enabled, int requestsPerMinute) {
    }
}
