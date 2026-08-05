package com.cobre.notifications.infrastructure.adapter.in.messaging;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.Receiver;

import java.nio.charset.StandardCharsets;

/**
 * Base de los adaptadores de entrada AMQP.
 *
 * <p>Confirmacion manual siempre: el mensaje se confirma despues de que el caso de
 * uso termino, no cuando se recibe. Si el proceso muere a mitad del procesamiento, el
 * broker reentrega y no se pierde la notificacion.
 *
 * <p>Un mensaje que falla se rechaza sin reencolar y cae a la cola muerta. Reencolar
 * un mensaje envenenado —uno que va a fallar siempre, como un JSON corrupto— lo
 * convierte en un bucle infinito que consume toda la capacidad del consumidor. En la
 * DLQ queda visible, alarmable e inspeccionable.
 *
 * <p>El consumo arranca con {@link ApplicationReadyEvent}, que ocurre despues de que
 * el {@code ApplicationRunner} declaro la topologia; suscribirse antes seria consumir
 * de una cola que todavia no existe.
 */
abstract class AbstractAmqpListener {

    private final Receiver receiver;
    private final int prefetch;
    private Disposable subscription;

    protected AbstractAmqpListener(Receiver receiver, int prefetch) {
        this.receiver = receiver;
        this.prefetch = prefetch;
    }

    protected abstract String queueName();

    protected abstract Logger logger();

    /** Procesa el cuerpo del mensaje. Un error hace que el mensaje vaya a la DLQ. */
    protected abstract Mono<Void> handle(String body);

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        // La concurrencia se topa con el prefetch: no tiene sentido procesar mas
        // mensajes en paralelo de los que el broker nos deja tener sin confirmar.
        subscription = receiver.consumeManualAck(queueName(), new ConsumeOptions().qos(prefetch))
                .flatMap(this::process, prefetch)
                .subscribe(
                        ignored -> { },
                        error -> logger().error("El consumo de '{}' termino con error", queueName(), error));
        logger().info("Escuchando la cola '{}' con prefetch {}", queueName(), prefetch);
    }

    private Mono<Void> process(AcknowledgableDelivery delivery) {
        String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
        return handle(body)
                .doOnSuccess(ignored -> delivery.ack())
                .onErrorResume(error -> {
                    logger().error("Mensaje de '{}' enviado a la cola muerta: {}",
                            queueName(), error.toString());
                    delivery.nack(false);
                    return Mono.empty();
                });
    }

    @PreDestroy
    public void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }
}
