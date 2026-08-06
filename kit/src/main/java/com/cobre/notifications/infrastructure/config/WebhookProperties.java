package com.cobre.notifications.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuracion del cliente de webhooks.
 *
 * @param requireHttps           exige HTTPS en la URL destino
 * @param blockInternalAddresses rechaza destinos que resuelvan a la red interna (anti-SSRF)
 * @param connectTimeout         tope para establecer la conexion TCP
 * @param responseTimeout        tope para recibir la respuesta completa
 * @param maxResponseBytes       cuerpo maximo que se lee del destino al registrar un error
 * @param overrideUrl            sustituye la URL de todas las suscripciones (solo demo)
 */
@ConfigurationProperties(prefix = "cobre.webhook")
public record WebhookProperties(
        boolean requireHttps,
        boolean blockInternalAddresses,
        Duration connectTimeout,
        Duration responseTimeout,
        int maxResponseBytes,
        String overrideUrl) {

    public WebhookProperties {
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(2);
        responseTimeout = responseTimeout != null ? responseTimeout : Duration.ofSeconds(5);
        maxResponseBytes = maxResponseBytes > 0 ? maxResponseBytes : 2048;
    }
}
