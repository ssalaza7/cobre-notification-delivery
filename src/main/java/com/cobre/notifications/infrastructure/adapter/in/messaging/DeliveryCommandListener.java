package com.cobre.notifications.infrastructure.adapter.in.messaging;

import com.cobre.notifications.application.port.in.DeliverNotificationEventUseCase;
import com.cobre.notifications.domain.exception.NotificationEventNotFoundException;
import com.cobre.notifications.infrastructure.adapter.out.messaging.DeliveryCommandMessage;
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
 * Adaptador de entrada: ejecuta las ordenes de entrega.
 *
 * <p>Atiende tanto las altas nuevas y los reenvios manuales como los reintentos que
 * vuelven de las colas de retardo: todos aterrizan en la misma cola, asi que hay un
 * unico camino de entrega y no dos implementaciones que se puedan desincronizar.
 */
@Component
public class DeliveryCommandListener extends AbstractAmqpListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryCommandListener.class);

    private final DeliverNotificationEventUseCase deliverUseCase;
    private final ObjectMapper objectMapper;
    private final String queue;

    public DeliveryCommandListener(
            Receiver receiver,
            MessagingProperties messaging,
            DeliverNotificationEventUseCase deliverUseCase,
            ObjectMapper objectMapper) {
        super(receiver, messaging.prefetch());
        this.deliverUseCase = deliverUseCase;
        this.objectMapper = objectMapper;
        this.queue = messaging.deliveryQueue();
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
        return Mono.fromCallable(() -> objectMapper.readValue(body, DeliveryCommandMessage.class))
                .flatMap(command -> deliverUseCase.deliver(command.eventId())
                        // Los identificadores viajan por el contexto reactivo hasta el MDC:
                        // un reintento ocurre minutos despues y en otro hilo, y sin esto
                        // sus lineas de log quedarian sueltas y sin forma de agruparlas.
                        .contextWrite(Context.of(
                                LogFields.EVENT_ID, command.eventId(),
                                LogFields.CLIENT_ID, command.clientId())))
                .doOnNext(outcome -> log.debug("Orden de entrega resuelta con desenlace {}", outcome))
                // Si el evento no existe no hay nada que reintentar ni que inspeccionar
                // en la DLQ: se confirma el mensaje y se deja constancia.
                .onErrorResume(NotificationEventNotFoundException.class, error -> {
                    log.warn("Orden de entrega descartada: {}", error.getMessage());
                    return Mono.empty();
                })
                .then();
    }
}
