package com.cobre.notifications.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion de seguridad de la API self-service.
 *
 * @param oidc      proveedor de identidad que emite y firma los tokens
 * @param rateLimit control de abuso por cliente
 */
@ConfigurationProperties(prefix = "cobre.security")
public record SecurityProperties(Oidc oidc, RateLimit rateLimit, boolean openMetrics) {

    /**
     * Deja {@code /actuator/prometheus} sin autenticacion.
     *
     * <p>Falso por defecto: un endpoint de metricas abierto entrega el mapa operativo del
     * sistema. Solo se activa en el perfil local, para que Prometheus lo raspe y se pueda
     * abrir en el navegador durante una demostracion. En produccion, ademas del scope,
     * lo correcto es moverlo a un puerto de gestion que no se publique a internet.
     */
    public boolean openMetrics() {
        return openMetrics;
    }

    /**
     * Proveedor de identidad.
     *
     * <p>El servicio ya no firma ni guarda secretos de firma: valida contra las claves
     * publicas que el proveedor publica en su JWKS, y este las rota sin que haya que
     * redesplegar nada.
     *
     * @param issuerUri identificador del emisor. Es lo que se compara con el claim
     *                  {@code iss} del token, y lo que impide aceptar uno emitido por
     *                  otro proveedor
     * @param jwkSetUri claves publicas con las que se verifica la firma
     * @param tokenUri  endpoint al que se reenvia el flujo {@code client_credentials}
     */
    public record Oidc(String issuerUri, String jwkSetUri, String tokenUri) {
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
