package com.cobre.notifications.infrastructure.adapter.in.messaging;

import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase;
import com.cobre.notifications.infrastructure.config.ConditionalOnRole;
import com.cobre.notifications.infrastructure.config.MessagingProperties;
import com.cobre.notifications.infrastructure.observability.LogFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.Receiver;
import reactor.util.context.Context;
import tools.jackson.databind.ObjectMapper;

/**
 * Adaptador de entrada: consume los eventos que la plataforma publica en su bus y los
 * da de alta para entrega.
 *
 * <p>Es el punto donde el servicio se acopla al resto de la plataforma, y el acople es
 * solo el contrato del mensaje. Si manana la plataforma migra de RabbitMQ a Kafka,
 * cambia esta clase y nada mas.
 */
@Component
@ConditionalOnRole(ConditionalOnRole.WORKER)
public class PlatformEventListener extends AbstractAmqpListener {

    private static final Logger log = LoggerFactory.getLogger(PlatformEventListener.class);

    private final IngestNotificationEventUseCase ingestUseCase;
    private final ObjectMapper objectMapper;
    private final String queue;

    public PlatformEventListener(
            Receiver receiver,
            MessagingProperties messaging,
            IngestNotificationEventUseCase ingestUseCase,
            ObjectMapper objectMapper) {
        super(receiver, messaging.prefetch());
        this.ingestUseCase = ingestUseCase;
        this.objectMapper = objectMapper;
        this.queue = messaging.inboundQueue();
    }

    @Override
    protected String queueName() {
        return queue;
    }

    @Override
    protected Logger logger() {
        return log;
    }

    @Override
    protected Mono<Void> handle(String body) {
        return Mono.fromCallable(() -> objectMapper.readValue(body, PlatformEventMessage.class))
                .flatMap(message -> ingestUseCase.ingest(message.toCommand())
                        .doOnNext(result -> log.debug("Evento de plataforma procesado: {}", result))
                        .contextWrite(Context.of(
                                LogFields.EVENT_ID, message.eventId(),
                                LogFields.CLIENT_ID, message.clientId())))
                .then();
    }
}
