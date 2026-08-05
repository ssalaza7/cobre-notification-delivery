package com.cobre.notifications.infrastructure.adapter.out.metrics;

import com.cobre.notifications.application.port.out.MetricsPort;
import com.cobre.notifications.domain.model.AttemptOutcome;
import com.cobre.notifications.domain.model.DeliveryStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publica el estado de la entrega en Prometheus.
 *
 * <p>Que puede vigilar el equipo de monitoreo con esto:
 * <ul>
 *   <li>{@code cobre_notification_delivery_attempts_total{outcome}} — tasa de fallo por
 *       intento. Un salto de {@code RETRYABLE_FAILURE} indica un destino degradado;
 *       uno de {@code PERMANENT_FAILURE}, un contrato roto.</li>
 *   <li>{@code cobre_notification_delivery_seconds} — latencia del webhook del cliente,
 *       con percentiles. Es lo primero que se degrada antes de que empiecen los timeouts.</li>
 *   <li>{@code cobre_notification_settled_total{status}} — entregas cerradas. La razon
 *       {@code failed/completed} es el SLI de la capacidad.</li>
 *   <li>{@code cobre_notification_backlog} — pendientes y en reintento en este momento.
 *       Es el gauge que dispara la alerta cuando la cola crece mas rapido de lo que se drena.</li>
 * </ul>
 *
 * <p>Ninguna metrica lleva {@code client_id} como etiqueta: con miles de clientes eso
 * multiplica las series de tiempo hasta tumbar a Prometheus. El corte por cliente se
 * hace sobre los logs estructurados y la API de detalle.
 */
@Component
public class MicrometerMetricsAdapter implements MetricsPort {

    private static final String ATTEMPTS = "cobre.notification.delivery.attempts";
    private static final String DURATION = "cobre.notification.delivery";
    private static final String SETTLED = "cobre.notification.settled";
    private static final String RETRIES = "cobre.notification.retry.scheduled";
    private static final String REPLAYS = "cobre.notification.replay.requested";
    private static final String INGESTED = "cobre.notification.ingested";
    private static final String BACKLOG = "cobre.notification.backlog";

    private final MeterRegistry registry;
    private final Map<DeliveryStatus, AtomicLong> backlog = new EnumMap<>(DeliveryStatus.class);

    public MicrometerMetricsAdapter(MeterRegistry registry) {
        this.registry = registry;
        for (DeliveryStatus status : DeliveryStatus.values()) {
            AtomicLong holder = new AtomicLong();
            backlog.put(status, holder);
            registry.gauge(BACKLOG, Tags.of("status", status.apiValue()), holder);
        }
    }

    @Override
    public void eventIngested(String eventType) {
        registry.counter(INGESTED, "event_type", eventType).increment();
    }

    @Override
    public void deliveryAttempted(String eventType, AttemptOutcome outcome, long durationMs) {
        registry.counter(ATTEMPTS, "event_type", eventType, "outcome", outcome.name()).increment();
        Timer.builder(DURATION)
                .tag("event_type", eventType)
                .tag("outcome", outcome.name())
                .publishPercentileHistogram()
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void deliverySettled(String eventType, DeliveryStatus finalStatus) {
        registry.counter(SETTLED, "event_type", eventType, "status", finalStatus.apiValue()).increment();
    }

    @Override
    public void retryScheduled(String eventType, int attemptNumber) {
        registry.counter(RETRIES, "event_type", eventType, "attempt", String.valueOf(attemptNumber)).increment();
    }

    @Override
    public void replayRequested(String eventType) {
        registry.counter(REPLAYS, "event_type", eventType).increment();
    }

    /** Lo invoca el refrescador periodico de backlog. */
    void updateBacklog(DeliveryStatus status, long count) {
        backlog.get(status).set(count);
    }
}
