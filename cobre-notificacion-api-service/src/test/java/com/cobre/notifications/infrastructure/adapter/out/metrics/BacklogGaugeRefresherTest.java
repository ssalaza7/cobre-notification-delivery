package com.cobre.notifications.infrastructure.adapter.out.metrics;

import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.domain.model.DeliveryStatus;
import com.cobre.notifications.domain.model.StatusCount;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El refrescador de backlog vive en la API y no en el worker: es observacion pura, y una
 * consulta periodica de conteo no debe competir por capacidad con la entrega.
 */
class BacklogGaugeRefresherTest {

    private SimpleMeterRegistry registry;
    private MicrometerMetricsAdapter adapter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        adapter = new MicrometerMetricsAdapter(registry);
    }

    private double gauge(String status) {
        return registry.get("cobre.notification.backlog").tag("status", status).gauge().value();
    }

    @Test
    @DisplayName("publica un gauge de backlog por cada estado, incluso los que estan en cero")
    void publica_el_backlog_completo() {
        NotificationEventRepositoryPort events = mock(NotificationEventRepositoryPort.class);
        when(events.countByStatus()).thenReturn(Flux.just(
                new StatusCount(DeliveryStatus.RETRYING, 7),
                new StatusCount(DeliveryStatus.COMPLETED, 120)));

        new BacklogGaugeRefresher(events, adapter).refresh();

        assertThat(gauge("retrying")).isEqualTo(7);
        assertThat(gauge("completed")).isEqualTo(120);
        // Sin filas en ese estado, el gauge debe decir cero y no quedarse en su valor anterior.
        assertThat(gauge("failed")).isZero();
        assertThat(gauge("pending")).isZero();
    }

    @Test
    @DisplayName("si la consulta de backlog falla, la aplicacion sigue sirviendo trafico")
    void tolera_fallos_al_refrescar() {
        NotificationEventRepositoryPort events = mock(NotificationEventRepositoryPort.class);
        when(events.countByStatus()).thenReturn(Flux.error(new IllegalStateException("base caida")));

        new BacklogGaugeRefresher(events, adapter).refresh();

        assertThat(gauge("pending")).isZero();
    }
}
