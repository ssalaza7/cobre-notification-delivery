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
 * <p>Ninguna firma recibe {@code clientId}: usarlo como etiqueta haria explotar la
 * cardinalidad de las series de Prometheus con miles de clientes. El corte por
 * cliente se hace sobre los logs estructurados y la base de datos.
 */
public interface MetricsPort {

    void eventIngested(String eventType);

    void deliveryAttempted(String eventType, AttemptOutcome outcome, long durationMs);

    void deliverySettled(String eventType, DeliveryStatus finalStatus);

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
