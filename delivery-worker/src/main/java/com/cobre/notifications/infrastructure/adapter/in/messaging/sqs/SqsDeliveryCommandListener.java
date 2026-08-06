package com.cobre.notifications.infrastructure.adapter.in.messaging.sqs;

import com.cobre.notifications.application.port.in.DeliverNotificationEventUseCase;
import com.cobre.notifications.domain.exception.NotificationEventNotFoundException;
import com.cobre.notifications.infrastructure.adapter.out.messaging.DeliveryCommandMessage;
import com.cobre.notifications.infrastructure.config.SqsProperties;
import com.cobre.notifications.infrastructure.observability.LogFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import tools.jackson.databind.ObjectMapper;

/** Ejecuta las ordenes de entrega que llegan por SQS: altas nuevas, reintentos y reenvios. */
public class SqsDeliveryCommandListener extends AbstractSqsListener {

    private static final Logger log = LoggerFactory.getLogger(SqsDeliveryCommandListener.class);

    private final DeliverNotificationEventUseCase deliverUseCase;
    private final ObjectMapper objectMapper;
    private final String queueUrl;

    public SqsDeliveryCommandListener(
            SqsAsyncClient sqs,
            SqsProperties properties,
            DeliverNotificationEventUseCase deliverUseCase,
            ObjectMapper objectMapper) {
        super(sqs, properties);
        this.deliverUseCase = deliverUseCase;
        this.objectMapper = objectMapper;
        this.queueUrl = properties.deliveryQueueUrl();
    }

    @Override
    protected String queueUrl() {
        return queueUrl;
    }

    @Override
    protected Logger logger() {
        return log;
    }

    @Override
    protected Mono<Void> handle(String body) {
        return Mono.fromCallable(() -> objectMapper.readValue(body, DeliveryCommandMessage.class))
                .flatMap(command -> deliverUseCase.deliver(command.eventId())
                        .contextWrite(Context.of(
                                LogFields.EVENT_ID, command.eventId(),
                                LogFields.CLIENT_ID, command.clientId())))
                .doOnNext(outcome -> log.debug("Orden de entrega resuelta con desenlace {}", outcome))
                // Un evento inexistente no se arregla reintentando: se confirma y se
                // deja constancia, en vez de ensuciar la cola muerta.
                .onErrorResume(NotificationEventNotFoundException.class, error -> {
                    log.warn("Orden de entrega descartada: {}", error.getMessage());
                    return Mono.empty();
                })
                .then();
    }
}
