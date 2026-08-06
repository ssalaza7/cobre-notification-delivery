package com.cobre.notifications.infrastructure.adapter.out.messaging;

import com.cobre.notifications.infrastructure.config.MessagingProvider;
import com.cobre.notifications.infrastructure.config.MessagingProperties;
import com.cobre.notifications.infrastructure.config.RetryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Sender;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Declara la topologia AMQP al arrancar.
 *
 * <p>El retardo de los reintentos se consigue con colas sin consumidor: el mensaje
 * espera ahi hasta expirar y RabbitMQ lo enruta por dead-letter de vuelta a la cola
 * de entrega. Hay una cola por escalon de backoff en vez de una sola con TTL variable
 * porque una cola de RabbitMQ solo expira mensajes desde la cabeza: con TTLs
 * mezclados, un mensaje de 30 minutos al frente bloquea a los de 5 segundos que
 * vienen detras.
 *
 * <p>Es el unico {@code block()} del proyecto y esta fuera del camino de cualquier
 * peticion: si el broker no esta disponible, el arranque falla rapido en vez de
 * aceptar trafico que no va a poder procesar.
 */
@Component
@MessagingProvider(MessagingProvider.RABBIT)
public class RabbitTopologyInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RabbitTopologyInitializer.class);

    private final Sender sender;
    private final MessagingProperties messaging;
    private final RetryProperties retry;

    public RabbitTopologyInitializer(
            Sender sender, MessagingProperties messaging, RetryProperties retry) {
        this.sender = sender;
        this.messaging = messaging;
        this.retry = retry;
    }

    @Override
    public void run(ApplicationArguments args) {
        declare().block();
        log.info("Topologia AMQP declarada: cola de entrega '{}', {} colas de retardo y DLQ '{}'",
                messaging.deliveryQueue(), retry.delays().size(), messaging.dlq());
    }

    Mono<Void> declare() {
        return Mono.when(
                        declareCoreTopology(),
                        declareInboundTopology(),
                        declareRetryTopology())
                .then();
    }

    private Mono<Void> declareCoreTopology() {
        Map<String, Object> deliveryArgs = new LinkedHashMap<>();
        // Lo que el consumidor rechaza sin reencolar cae directo a la cola muerta.
        deliveryArgs.put("x-dead-letter-exchange", messaging.exchange());
        deliveryArgs.put("x-dead-letter-routing-key", messaging.dlqRoutingKey());

        return sender.declareExchange(ExchangeSpecification.exchange(messaging.exchange())
                        .type("direct").durable(true))
                .then(sender.declareQueue(QueueSpecification.queue(messaging.deliveryQueue())
                        .durable(true).arguments(deliveryArgs)))
                .then(sender.bind(BindingSpecification.binding(
                        messaging.exchange(), messaging.deliveryRoutingKey(), messaging.deliveryQueue())))
                .then(sender.declareQueue(QueueSpecification.queue(messaging.dlq()).durable(true)))
                .then(sender.bind(BindingSpecification.binding(
                        messaging.exchange(), messaging.dlqRoutingKey(), messaging.dlq())))
                .then();
    }

    /** Cola propia enganchada al bus de la plataforma; el exchange lo publica otro equipo. */
    private Mono<Void> declareInboundTopology() {
        return sender.declareExchange(ExchangeSpecification.exchange(messaging.platformExchange())
                        .type("topic").durable(true))
                .then(sender.declareQueue(QueueSpecification.queue(messaging.inboundQueue()).durable(true)))
                .then(sender.bind(BindingSpecification.binding(
                        messaging.platformExchange(), messaging.inboundRoutingKey(), messaging.inboundQueue())))
                .then();
    }

    private Mono<Void> declareRetryTopology() {
        return sender.declareExchange(ExchangeSpecification.exchange(messaging.retryExchange())
                        .type("direct").durable(true))
                .thenMany(Flux.fromIterable(retry.delays()).concatMap(this::declareRetryLevel))
                .then();
    }

    private Mono<Void> declareRetryLevel(Duration delay) {
        Map<String, Object> args = new LinkedHashMap<>();
        // Al expirar, el mensaje vuelve solo a la cola de entrega.
        args.put("x-dead-letter-exchange", messaging.exchange());
        args.put("x-dead-letter-routing-key", messaging.deliveryRoutingKey());

        String queue = messaging.retryQueue(delay);
        return sender.declareQueue(QueueSpecification.queue(queue).durable(true).arguments(args))
                .then(sender.bind(BindingSpecification.binding(
                        messaging.retryExchange(), messaging.retryRoutingKey(delay), queue)))
                .then();
    }
}
