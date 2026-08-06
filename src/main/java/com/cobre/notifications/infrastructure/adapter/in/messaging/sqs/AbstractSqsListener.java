package com.cobre.notifications.infrastructure.adapter.in.messaging.sqs;

import com.cobre.notifications.infrastructure.config.SqsProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.Duration;

/**
 * Base de los adaptadores de entrada sobre SQS.
 *
 * <p>El modelo es distinto al de AMQP y conviene tenerlo claro: RabbitMQ <b>empuja</b>
 * mensajes al consumidor, mientras que SQS obliga a <b>pedirlos</b>. Aqui se usa long
 * polling con espera de 20 segundos, que es el maximo: reduce la latencia a casi cero
 * cuando hay trafico y evita pagar por sondeos en vacio cuando no lo hay.
 *
 * <p>La confirmacion tambien cambia de forma pero no de fondo. En AMQP se confirma con
 * un ack; en SQS se <b>borra</b> el mensaje. Mientras no se borre, el mensaje reaparece
 * al vencer el visibility timeout. El efecto es el mismo: si el proceso muere a mitad
 * del procesamiento, el mensaje vuelve y no se pierde la notificacion.
 *
 * <p>Un mensaje que falla simplemente no se borra. SQS lo reentrega y, tras las
 * entregas que fije la redrive policy de la cola, lo manda solo a la cola muerta. Es
 * la misma proteccion contra mensajes envenenados que en AMQP se consigue rechazando
 * sin reencolar, pero aqui la cuenta la lleva el broker.
 */
abstract class AbstractSqsListener {

    private final SqsAsyncClient sqs;
    private final SqsProperties properties;
    private Disposable subscription;

    protected AbstractSqsListener(SqsAsyncClient sqs, SqsProperties properties) {
        this.sqs = sqs;
        this.properties = properties;
    }

    protected abstract String queueUrl();

    protected abstract Logger logger();

    protected abstract Mono<Void> handle(String body);

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl())
                .maxNumberOfMessages(properties.maxMessages())
                .waitTimeSeconds((int) properties.waitTime().toSeconds())
                .visibilityTimeout((int) properties.visibilityTimeout().toSeconds())
                .build();

        subscription = Mono.fromFuture(() -> sqs.receiveMessage(request))
                .flatMapIterable(ReceiveMessageResponse::messages)
                .flatMap(this::process, properties.maxMessages())
                // repeat() vuelve a sondear apenas termina el lote anterior.
                .repeat()
                // Un fallo de red contra SQS no debe matar el consumidor para siempre.
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(signal -> logger().warn(
                                "Fallo el sondeo de la cola; se reintenta: {}",
                                signal.failure().toString())))
                .subscribe(
                        ignored -> { },
                        error -> logger().error("El consumo de la cola termino con error", error));

        logger().info("Sondeando la cola SQS {} (lotes de {}, espera de {}s)",
                queueUrl(), properties.maxMessages(), properties.waitTime().toSeconds());
    }

    private Mono<Void> process(Message message) {
        return handle(message.body())
                .then(Mono.defer(() -> delete(message)))
                .onErrorResume(error -> {
                    // No se borra: SQS lo reentregara y, agotadas las entregas, ira
                    // solo a la cola muerta segun la redrive policy.
                    logger().error("Mensaje no procesado, quedara para reentrega: {}", error.toString());
                    return Mono.empty();
                });
    }

    private Mono<Void> delete(Message message) {
        return Mono.fromFuture(() -> sqs.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl())
                        .receiptHandle(message.receiptHandle())
                        .build()))
                .then();
    }

    /** Expuesto para las pruebas: permite ejecutar un lote sin arrancar el bucle. */
    Flux<Void> processBatch(Iterable<Message> messages) {
        return Flux.fromIterable(messages).flatMap(this::process);
    }

    @PreDestroy
    public void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }
}
