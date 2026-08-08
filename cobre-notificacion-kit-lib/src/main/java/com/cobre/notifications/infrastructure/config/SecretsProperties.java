package com.cobre.notifications.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion del almacen de secretos de firma.
 *
 * @param region           region de AWS
 * @param endpoint         direccion alterna del servicio. Vacio en AWS; se define para
 *                         apuntar a un emulador local
 * @param signingKeyPrefix prefijo bajo el que viven los secretos de firma. Existe para
 *                         poder acotar los permisos: la api crea solo aqui debajo y el
 *                         worker lee solo de aqui debajo
 * @param cacheTtl         cuanto se conserva en memoria una suscripcion ya resuelta. El
 *                         worker la necesita en cada intento y cada lectura del almacen
 *                         se factura; sin cache, un webhook caido genera una llamada por
 *                         reintento. A cambio, un secreto rotado tarda hasta este plazo
 *                         en aplicarse
 */
@ConfigurationProperties(prefix = "cobre.secrets")
public record SecretsProperties(
        String region,
        String endpoint,
        String signingKeyPrefix,
        Duration cacheTtl) {

    public SecretsProperties {
        region = region != null && !region.isBlank() ? region : "us-east-1";
        signingKeyPrefix = signingKeyPrefix != null && !signingKeyPrefix.isBlank()
                ? signingKeyPrefix
                : "cobre/webhook-signing/";
        cacheTtl = cacheTtl != null ? cacheTtl : Duration.ofSeconds(60);
    }
}
