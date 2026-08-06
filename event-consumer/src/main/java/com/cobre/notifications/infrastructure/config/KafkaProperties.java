package com.cobre.notifications.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Conexion al bus de eventos de la plataforma.
 *
 * @param bootstrapServers direccion del cluster
 * @param topic            topic donde la plataforma publica sus eventos
 * @param groupId          grupo de consumo; todas las replicas del worker comparten uno
 *                         solo, para repartirse las particiones en vez de duplicar el
 *                         trabajo
 */
@ConfigurationProperties(prefix = "cobre.kafka")
public record KafkaProperties(String bootstrapServers, String topic, String groupId) {

    public KafkaProperties {
        bootstrapServers = bootstrapServers != null ? bootstrapServers : "localhost:9092";
        topic = topic != null ? topic : "cobre.platform.events";
        groupId = groupId != null ? groupId : "notification-delivery-service";
    }
}
