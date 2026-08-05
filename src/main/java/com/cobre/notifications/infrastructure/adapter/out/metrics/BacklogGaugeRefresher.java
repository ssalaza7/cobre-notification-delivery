package com.cobre.notifications.infrastructure.adapter.out.metrics;

import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.domain.model.DeliveryStatus;
import com.cobre.notifications.infrastructure.config.ConditionalOnRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Refresca los gauges de backlog consultando la base periodicamente.
 *
 * <p>Se hace por muestreo y no incrementando contadores en cada transicion porque el
 * servicio corre en varias instancias: un contador en memoria solo conoce lo que paso
 * en su propio proceso, mientras que la consulta ve el estado real compartido. El
 * costo es un {@code GROUP BY} cada 15 segundos sobre una tabla indexada, que a
 * cambio da una senal correcta para alertar.
 */
@Component
@ConditionalOnRole(ConditionalOnRole.WORKER)
public class BacklogGaugeRefresher {

    private static final Logger log = LoggerFactory.getLogger(BacklogGaugeRefresher.class);

    private final NotificationEventRepositoryPort events;
    private final MicrometerMetricsAdapter metrics;

    public BacklogGaugeRefresher(NotificationEventRepositoryPort events, MicrometerMetricsAdapter metrics) {
        this.events = events;
        this.metrics = metrics;
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT15S")
    public void refresh() {
        events.countByStatus()
                .collectList()
                .subscribe(counts -> {
                    Map<DeliveryStatus, Long> byStatus = new EnumMap<>(DeliveryStatus.class);
                    counts.forEach(count -> byStatus.put(count.status(), count.count()));
                    // Los estados sin filas deben publicarse en cero: si no, el gauge
                    // se queda con el ultimo valor y la alerta nunca se apaga.
                    for (DeliveryStatus status : DeliveryStatus.values()) {
                        metrics.updateBacklog(status, byStatus.getOrDefault(status, 0L));
                    }
                }, error -> log.warn("No se pudo refrescar el backlog de notificaciones: {}", error.toString()));
    }
}
