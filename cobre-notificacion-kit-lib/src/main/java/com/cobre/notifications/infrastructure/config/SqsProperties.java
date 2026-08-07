package com.cobre.notifications.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuracion de la cola de trabajo sobre SQS.
 *
 * @param region             region de AWS
 * @param endpoint           direccion alterna del servicio. Se usa para apuntar a un
 *                           emulador local; vacio en AWS, donde vale el endpoint real
 * @param deliveryQueueUrl   cola de la que come el worker
 * @param deadLetterQueueUrl destino de lo que ya no se reintenta
 * @param maxMessages        mensajes por lote de sondeo; el tope de SQS es 10
 * @param waitTime           espera del sondeo largo. 20s es el maximo y el que menos
 *                           cuesta: sin el, cada sondeo en vacio es una llamada facturada
 * @param visibilityTimeout  cuanto queda invisible un mensaje mientras se procesa. Si el
 *                           proceso muere antes de borrarlo, reaparece pasado este plazo
 */
@ConfigurationProperties(prefix = "cobre.sqs")
public record SqsProperties(
        String region,
        String endpoint,
        String deliveryQueueUrl,
        String deadLetterQueueUrl,
        int maxMessages,
        Duration waitTime,
        Duration visibilityTimeout) {

    /**
     * Tope de retardo por mensaje que impone SQS.
     *
     * <p>Es una restriccion real de la plataforma y condiciona la politica de
     * reintentos: ningun escalon de backoff puede superar los 15 minutos. Conviene
     * decirlo antes de que lo pregunten, porque acota cuanto puede esperar el sistema
     * a que un destino caido se recupere por si solo.
     */
    public static final Duration MAX_DELAY = Duration.ofSeconds(900);

    public SqsProperties {
        region = region != null && !region.isBlank() ? region : "us-east-1";
        maxMessages = maxMessages > 0 ? Math.min(maxMessages, 10) : 10;
        waitTime = waitTime != null ? waitTime : Duration.ofSeconds(20);
        visibilityTimeout = visibilityTimeout != null ? visibilityTimeout : Duration.ofSeconds(60);
    }
}
