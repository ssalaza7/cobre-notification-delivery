package com.cobre.notifications.application.port.out;

import com.cobre.notifications.domain.model.AttemptOutcome;
import com.cobre.notifications.domain.model.DeliveryStatus;

/**
 * Puerto de salida de observabilidad.
 *
 * <p>Existe como puerto y no como una llamada directa a Micrometer para que el caso
 * de uso pueda verificarse en pruebas ("cuando se agotan los reintentos, se cuenta
 * un fallo definitivo") sin levantar un registro de metricas.
 *
 * <p>Salvo {@link #clientDeliveryFailing(String)}, ninguna firma recibe {@code clientId}:
 * usarlo como etiqueta en todas las metricas haria explotar la cardinalidad de las series
 * de Prometheus con miles de clientes.
 */
public interface MetricsPort {

    void eventIngested(String eventType);

    /**
     * @param httpStatus codigo devuelto por el webhook, o {@code null} si no respondio
     *                   (timeout o conexion rechazada)
     */
    void deliveryAttempted(String eventType, AttemptOutcome outcome, long durationMs, Integer httpStatus);

    /**
     * @param afterRetries si la entrega necesito mas de un intento. Distingue lo que
     *                     salio bien a la primera de lo que se recupero gracias al
     *                     backoff, que son dos senales operativas distintas
     */
    void deliverySettled(String eventType, DeliveryStatus finalStatus, boolean afterRetries);

    /**
     * Una entrega hacia ese cliente acaba de fallar.
     *
     * <p>Es la <b>unica</b> metrica etiquetada por cliente, y existe porque cuando un
     * webhook se cae la primera pregunta de guardia es "de quien". Sin esta etiqueta hay
     * que ir a los logs, que es mas lento justo cuando el tiempo importa, y no se puede
     * alertar automaticamente.
     *
     * <p>La cardinalidad se sostiene porque la serie solo nace cuando un cliente falla:
     * la cota no son todos los clientes, son los que estan fallando ahora. Prometheus
     * deja de exponer la serie cuando el proceso reinicia, y en Datadog la metrica
     * caduca sin datos.
     */
    void clientDeliveryFailing(String clientId);

    void retryScheduled(String eventType, int attemptNumber);

    void replayRequested(String eventType);

    /** Token emitido correctamente. */
    void accessTokenIssued();

    /**
     * Intento de autenticacion rechazado.
     *
     * <p>Es la senal que permite alertar por fuerza bruta: un salto en esta metrica
     * sin un salto equivalente en los tokens emitidos es alguien probando secretos.
     */
    void accessTokenDenied();
}
