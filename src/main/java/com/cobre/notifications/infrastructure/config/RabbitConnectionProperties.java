package com.cobre.notifications.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Datos de conexion al broker.
 *
 * <p>Son propias y no {@code spring.rabbitmq.*} porque el proyecto no usa
 * {@code spring-boot-starter-amqp}: la publicacion y el consumo van por
 * reactor-rabbitmq, sin el stack bloqueante de plantillas y contenedores de listeners.
 */
@ConfigurationProperties(prefix = "cobre.rabbitmq")
public record RabbitConnectionProperties(String host, int port, String username, String password) {

    public RabbitConnectionProperties {
        host = host != null ? host : "localhost";
        port = port > 0 ? port : 5672;
        username = username != null ? username : "guest";
        password = password != null ? password : "guest";
    }
}
