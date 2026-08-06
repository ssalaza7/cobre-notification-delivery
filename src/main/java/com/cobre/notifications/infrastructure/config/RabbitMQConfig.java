package com.cobre.notifications.infrastructure.config;

import com.rabbitmq.client.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

/**
 * Conexion reactiva a RabbitMQ.
 *
 * <p>{@code Sender} y {@code Receiver} usan conexiones separadas a proposito: si el
 * flujo de publicacion se satura, el consumo no se ve arrastrado, y RabbitMQ puede
 * aplicar backpressure a uno sin bloquear al otro.
 */
@Configuration
@MessagingProvider(MessagingProvider.RABBIT)
public class RabbitMQConfig {

    @Bean
    ConnectionFactory rabbitConnectionFactory(RabbitConnectionProperties properties) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(properties.host());
        factory.setPort(properties.port());
        factory.setUsername(properties.username());
        factory.setPassword(properties.password());
        factory.useNio();
        return factory;
    }

    @Bean(destroyMethod = "close")
    Sender rabbitSender(ConnectionFactory connectionFactory) {
        return RabbitFlux.createSender(new SenderOptions()
                .connectionFactory(connectionFactory)
                .connectionSupplier(cf -> cf.newConnection("notifications-sender")));
    }

    @Bean(destroyMethod = "close")
    Receiver rabbitReceiver(ConnectionFactory connectionFactory) {
        return RabbitFlux.createReceiver(new ReceiverOptions()
                .connectionFactory(connectionFactory)
                .connectionSupplier(cf -> cf.newConnection("notifications-receiver")));
    }
}
