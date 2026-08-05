package com.cobre.notifications.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuracion de seguridad de la API self-service.
 *
 * @param jwt       clave simetrica para validar los tokens
 * @param rateLimit control de abuso por cliente
 */
@ConfigurationProperties(prefix = "cobre.security")
public record SecurityProperties(Jwt jwt, RateLimit rateLimit) {

    /**
     * @param secret   clave HS256. Debe tener al menos 32 bytes; en produccion se
     *                 inyecta por variable de entorno y lo natural es migrar a
     *                 validacion por JWKS contra un proveedor OIDC.
     * @param tokenTtl vigencia del token emitido. Corta a proposito: un JWT no se puede
     *                 revocar sin montar una lista de revocacion, asi que la vida breve
     *                 es el unico control real sobre un token filtrado.
     * @param issuer   identificador del emisor, que viaja en el claim {@code iss}
     */
    public record Jwt(String secret, Duration tokenTtl, String issuer) {

        public Jwt {
            tokenTtl = tokenTtl != null ? tokenTtl : Duration.ofHours(1);
            issuer = issuer != null && !issuer.isBlank() ? issuer : "cobre-notification-delivery-service";
        }
    }

    /**
     * @param requestsPerMinute      cuota por cliente autenticado en el resto de la API
     * @param tokenRequestsPerMinute cuota por direccion de origen en el endpoint de
     *                               token; mucho mas baja porque ahi lo que se contiene
     *                               es la prueba de secretos a ciegas
     */
    public record RateLimit(boolean enabled, int requestsPerMinute, int tokenRequestsPerMinute) {

        public RateLimit {
            requestsPerMinute = requestsPerMinute > 0 ? requestsPerMinute : 120;
            tokenRequestsPerMinute = tokenRequestsPerMinute > 0 ? tokenRequestsPerMinute : 10;
        }
    }
}
