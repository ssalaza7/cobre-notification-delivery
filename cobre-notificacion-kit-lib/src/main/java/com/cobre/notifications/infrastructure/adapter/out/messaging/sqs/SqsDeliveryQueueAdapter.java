package com.cobre.notifications.infrastructure.adapter.out.messaging.sqs;

import com.cobre.notifications.application.port.out.DeliveryQueuePort;
import com.cobre.notifications.infrastructure.adapter.out.messaging.DeliveryCommandMessage;
import com.cobre.notifications.infrastructure.config.SqsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * Adaptador de salida hacia SQS.
 *
 * <p>Tres propiedades de SQS explican por que es la cola de trabajo correcta aqui:
 *
 * <ul>
 *   <li><b>El retardo es nativo.</b> {@code DelaySeconds} por mensaje es todo el
 *       backoff: no hacen falta colas de espera ni reglas de dead-letter para
 *       devolverlos. Menos piezas que declarar y que puedan desincronizarse.</li>
 *   <li><b>El tope es de 15 minutos.</b> Es una restriccion real de la plataforma y
 *       condiciona la politica de reintentos: ningun escalon puede superarla.</li>
 *   <li><b>No hay orden.</b> Una cola estandar no lo promete, y esa "carencia" es
 *       justamente lo que impide que el webhook lento de un cliente bloquee a los
 *       demas.</li>
 * </ul>
 */
public class SqsDeliveryQueueAdapter implements DeliveryQueuePort {

    private static final Logger log = LoggerFactory.getLogger(SqsDeliveryQueueAdapter.class);

    private final SqsAsyncClient sqs;
    private final SqsProperties properties;
    private final ObjectMapper objectMapper;

    public SqsDeliveryQueueAdapter(
            SqsAsyncClient sqs, SqsProperties properties, ObjectMapper objectMapper) {
        this.sqs = sqs;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> enqueue(String eventId, String clientId) {
        return send(properties.deliveryQueueUrl(), eventId, clientId, Duration.ZERO);
    }

    @Override
    public Mono<Void> enqueueRetry(String eventId, String clientId, Duration delay) {
        return send(properties.deliveryQueueUrl(), eventId, clientId, capped(delay));
    }

    /**
     * Recorta la espera al maximo que admite SQS.
     *
     * <p>Se recorta en vez de fallar: quedarse corto en el backoff es molesto, pero
     * perder el reintento por una excepcion seria perder la notificacion.
     */
    private Duration capped(Duration delay) {
        if (delay.compareTo(SqsProperties.MAX_DELAY) <= 0) {
            return delay;
        }
        log.warn("La espera de {} supera el maximo de SQS; se recorta a {}",
                delay, SqsProperties.MAX_DELAY);
        return SqsProperties.MAX_DELAY;
    }

    private Mono<Void> send(String queueUrl, String eventId, String clientId, Duration delay) {
        SendMessageRequest request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(objectMapper.writeValueAsString(new DeliveryCommandMessage(eventId, clientId)))
                .delaySeconds((int) delay.toSeconds())
                .build();

        return Mono.fromFuture(() -> sqs.sendMessage(request))
                .doOnError(error -> log.error("SQS rechazo la publicacion de {}: {}", eventId, error.toString()))
                .then();
    }

}
