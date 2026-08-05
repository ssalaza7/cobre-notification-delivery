package com.cobre.notifications.infrastructure.adapter.out.messaging;

import com.cobre.notifications.application.port.out.DeliveryQueuePort;
import com.cobre.notifications.infrastructure.config.MessagingProperties;
import com.cobre.notifications.infrastructure.config.RetryProperties;
import com.rabbitmq.client.AMQP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.Sender;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

/**
 * Adaptador de salida hacia RabbitMQ.
 *
 * <p>Publica siempre con confirmaciones del broker: si el broker no confirma, el
 * {@code Mono} falla y el consumidor no confirma su propio mensaje, de modo que la
 * entrega se reintenta en vez de desaparecer en silencio. Es lo que sostiene la
 * garantia at-least-once.
 */
@Component
public class RabbitDeliveryQueueAdapter implements DeliveryQueuePort {

    private static final Logger log = LoggerFactory.getLogger(RabbitDeliveryQueueAdapter.class);
    private static final int PERSISTENT = 2;

    private final Sender sender;
    private final MessagingProperties messaging;
    private final RetryProperties retry;
    private final ObjectMapper objectMapper;

    public RabbitDeliveryQueueAdapter(
            Sender sender,
            MessagingProperties messaging,
            RetryProperties retry,
            ObjectMapper objectMapper) {
        this.sender = sender;
        this.messaging = messaging;
        this.retry = retry;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> enqueue(String eventId, String clientId) {
        return publish(new OutboundMessage(
                messaging.exchange(),
                messaging.deliveryRoutingKey(),
                properties(null, Map.of()),
                body(eventId, clientId)));
    }

    @Override
    public Mono<Void> enqueueRetry(String eventId, String clientId, Duration delay) {
        Duration level = levelFor(delay);
        // El TTL por mensaje incluye el jitter; como todos los mensajes de una misma
        // cola tienen TTL parecido, el bloqueo de cabeza queda acotado a esa ventana.
        return publish(new OutboundMessage(
                messaging.retryExchange(),
                messaging.retryRoutingKey(level),
                properties(String.valueOf(delay.toMillis()), Map.of("x-cobre-retry-level", level.toString())),
                body(eventId, clientId)));
    }

    @Override
    public Mono<Void> sendToDeadLetter(String eventId, String clientId, String reason) {
        return publish(new OutboundMessage(
                messaging.exchange(),
                messaging.dlqRoutingKey(),
                properties(null, Map.of("x-cobre-dead-letter-reason", reason)),
                body(eventId, clientId)));
    }

    /**
     * Escalon de retardo al que corresponde una espera ya con jitter aplicado.
     *
     * <p>Se elige el mayor escalon que no supere la espera pedida. Funciona porque
     * cada escalon es mas del doble que el anterior y el jitter maximo es del 20%:
     * una espera jitterada nunca alcanza el escalon siguiente.
     */
    private Duration levelFor(Duration delay) {
        Duration selected = retry.delays().get(0);
        for (Duration candidate : retry.delays()) {
            if (candidate.compareTo(delay) <= 0) {
                selected = candidate;
            }
        }
        return selected;
    }

    private Mono<Void> publish(OutboundMessage message) {
        return sender.sendWithPublishConfirms(Flux.just(message))
                .flatMap(this::requireAck)
                .then();
    }

    private Mono<OutboundMessageResult> requireAck(OutboundMessageResult result) {
        if (result.isAck()) {
            return Mono.just(result);
        }
        log.error("RabbitMQ no confirmo la publicacion hacia '{}'", result.getOutboundMessage().getRoutingKey());
        return Mono.error(new IllegalStateException(
                "RabbitMQ rechazo la publicacion hacia " + result.getOutboundMessage().getRoutingKey()));
    }

    private AMQP.BasicProperties properties(String expirationMillis, Map<String, Object> headers) {
        AMQP.BasicProperties.Builder builder = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .deliveryMode(PERSISTENT)
                .headers(headers);
        if (expirationMillis != null) {
            builder.expiration(expirationMillis);
        }
        return builder.build();
    }

    private byte[] body(String eventId, String clientId) {
        return objectMapper.writeValueAsBytes(new DeliveryCommandMessage(eventId, clientId));
    }
}
