package com.cobre.notifications.infrastructure.adapter.in.messaging.kafka;

import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase;
import com.cobre.notifications.infrastructure.adapter.in.messaging.PlatformEventMessage;
import com.cobre.notifications.infrastructure.config.KafkaProperties;
import com.cobre.notifications.infrastructure.observability.LogFields;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.context.Context;
import reactor.util.retry.Retry;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * Consume los eventos que la plataforma publica en Kafka.
 *
 * <p>Es el punto donde este servicio se engancha al resto de la plataforma, y el unico
 * acople es el contrato del mensaje. Si manana la plataforma cambiara de bus, se
 * reescribe esta clase y nada mas: el caso de uso recibe el mismo comando venga de
 * donde venga.
 *
 * <p>Kafka funciona distinto a una cola y conviene tenerlo claro:
 *
 * <ul>
 *   <li><b>El mensaje no se borra al leerlo.</b> Queda en el topic durante su periodo de
 *       retencion, asi que otros servicios pueden leer el mismo evento y este podria
 *       reprocesar desde atras si hiciera falta.</li>
 *   <li><b>Lo que avanza es un puntero</b> (el offset). Confirmar significa mover ese
 *       puntero, no eliminar nada.</li>
 *   <li><b>El paralelismo lo topan las particiones.</b> Diez replicas del worker sobre
 *       un topic de tres particiones dejan siete sin trabajo. Es una de las razones por
 *       las que la <i>entrega</i> no se hace sobre Kafka sino sobre una cola.</li>
 * </ul>
 *
 * <p>El offset se confirma despues de procesar, nunca al recibir: si el proceso muere a
 * mitad, Kafka reentrega desde el ultimo confirmado y no se pierde el evento.
 */
public class KafkaPlatformEventListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaPlatformEventListener.class);

    private final KafkaReceiver<String, String> receiver;
    private final IngestNotificationEventUseCase ingestUseCase;
    private final ObjectMapper objectMapper;
    private final String topic;
    private Disposable subscription;

    public KafkaPlatformEventListener(
            KafkaReceiver<String, String> receiver,
            IngestNotificationEventUseCase ingestUseCase,
            ObjectMapper objectMapper,
            KafkaProperties properties) {
        this.receiver = receiver;
        this.ingestUseCase = ingestUseCase;
        this.objectMapper = objectMapper;
        this.topic = properties.topic();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        subscription = receiver.receive()
                .concatMap(this::process)
                // Una caida del cluster no debe matar al consumidor para siempre.
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(signal -> log.warn(
                                "Fallo el consumo de Kafka; se reintenta: {}", signal.failure().toString())))
                .subscribe(
                        ignored -> { },
                        error -> log.error("El consumo del topic '{}' termino con error", topic, error));

        log.info("Consumiendo el topic de Kafka '{}'", topic);
    }

    private Mono<Void> process(ReceiverRecord<String, String> record) {
        PlatformEventMessage message;
        try {
            message = objectMapper.readValue(record.value(), PlatformEventMessage.class);
        } catch (RuntimeException e) {
            // Un mensaje ilegible no mejora reintentandolo y bloquearia la particion
            // entera. Se confirma y queda registrado para inspeccion.
            log.error("Mensaje descartado por no poder deserializarse: {}", e.toString());
            record.receiverOffset().acknowledge();
            return Mono.empty();
        }

        return ingestUseCase.ingest(message.toCommand())
                .doOnNext(result -> log.debug("Evento de plataforma procesado: {}", result))
                .contextWrite(Context.of(
                        LogFields.EVENT_ID, message.eventId(),
                        LogFields.CLIENT_ID, message.clientId()))
                // Confirmar el offset es lo ultimo: hasta aqui, si el proceso muere,
                // Kafka reentrega desde el anterior.
                .doOnSuccess(ignored -> record.receiverOffset().acknowledge())
                // Un fallo de la base o de la cola NO se confirma: es transitorio y el
                // evento debe volver. Confirmarlo aqui —como se hacia antes, con un
                // unico catch para todo— dejaba el evento escrito en base, sin mensaje
                // en cola y sin reentrega: perdido en silencio.
                .doOnError(error -> log.error(
                        "Fallo transitorio ingestando {}; no se confirma el offset y Kafka reentregara: {}",
                        message.eventId(), error.toString()))
                .then();
    }

    @PreDestroy
    public void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }
}
