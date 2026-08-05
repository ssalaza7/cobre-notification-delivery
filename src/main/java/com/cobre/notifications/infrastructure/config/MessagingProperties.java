package com.cobre.notifications.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Nombres de la topologia AMQP. Se declaran por codigo al arrancar, nunca a mano. */
@ConfigurationProperties(prefix = "cobre.messaging")
public record MessagingProperties(
        String platformExchange,
        String inboundQueue,
        String inboundRoutingKey,
        String exchange,
        String deliveryQueue,
        String deliveryRoutingKey,
        String retryExchange,
        String dlq,
        String dlqRoutingKey,
        int prefetch) {

    /** Nombre de la cola de retardo correspondiente a un escalon de la politica de reintentos. */
    public String retryQueue(Duration delay) {
        return deliveryQueue + ".retry." + delay.toSeconds() + "s";
    }

    /** Routing key con la que se publica hacia esa cola de retardo. */
    public String retryRoutingKey(Duration delay) {
        return "retry." + delay.toSeconds() + "s";
    }

    public List<String> retryQueues(List<Duration> delays) {
        return delays.stream().map(this::retryQueue).toList();
    }
}
