package com.cobre.notifications.infrastructure.adapter.out.metrics;

import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.domain.model.AttemptOutcome;
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

class MicrometerMetricsAdapterTest {

    private SimpleMeterRegistry registry;
    private MicrometerMetricsAdapter adapter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        adapter = new MicrometerMetricsAdapter(registry);
    }

    @Test
    @DisplayName("cuenta los intentos separando exito de fallo y mide la latencia del webhook")
    void mide_los_intentos() {
        adapter.deliveryAttempted("credit_transfer", AttemptOutcome.DELIVERED, 120, 200);
        adapter.deliveryAttempted("credit_transfer", AttemptOutcome.RETRYABLE_FAILURE, 5000, 503);
        adapter.deliveryAttempted("credit_transfer", AttemptOutcome.RETRYABLE_FAILURE, 4800, 503);

        assertThat(registry.counter("cobre.notification.delivery.attempts",
                "event_type", "credit_transfer", "outcome", "DELIVERED").count()).isEqualTo(1);
        assertThat(registry.counter("cobre.notification.delivery.attempts",
                "event_type", "credit_transfer", "outcome", "RETRYABLE_FAILURE").count()).isEqualTo(2);

        assertThat(registry.timer("cobre.notification.delivery",
                "event_type", "credit_transfer", "outcome", "DELIVERED").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("cuenta los errores por codigo de respuesta del destino")
    void cuenta_los_errores_por_codigo() {
        adapter.deliveryAttempted("credit_transfer", AttemptOutcome.RETRYABLE_FAILURE, 5000, 503);
        adapter.deliveryAttempted("debit_purchase", AttemptOutcome.RETRYABLE_FAILURE, 4000, 503);
        adapter.deliveryAttempted("credit_transfer", AttemptOutcome.PERMANENT_FAILURE, 80, 400);
        // Timeout o conexion rechazada: no hay codigo que registrar.
        adapter.deliveryAttempted("credit_transfer", AttemptOutcome.RETRYABLE_FAILURE, 5000, null);

        assertThat(registry.counter("cobre.notification.delivery.errors", "http_status", "503").count())
                .isEqualTo(2);
        assertThat(registry.counter("cobre.notification.delivery.errors", "http_status", "400").count())
                .isEqualTo(1);
        assertThat(registry.counter("cobre.notification.delivery.errors", "http_status", "sin_respuesta").count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("una entrega exitosa no cuenta como error")
    void una_entrega_exitosa_no_es_error() {
        adapter.deliveryAttempted("credit_transfer", AttemptOutcome.DELIVERED, 120, 200);

        assertThat(registry.find("cobre.notification.delivery.errors").counters()).isEmpty();
    }

    @Test
    @DisplayName("cuenta las entregas cerradas por estado final, separando las recuperadas")
    void cuenta_las_entregas_cerradas() {
        adapter.deliverySettled("credit_transfer", DeliveryStatus.COMPLETED, false);
        adapter.deliverySettled("credit_transfer", DeliveryStatus.COMPLETED, true);
        adapter.deliverySettled("credit_transfer", DeliveryStatus.FAILED, true);

        assertThat(registry.counter("cobre.notification.settled", "event_type", "credit_transfer",
                "status", "completed", "after_retries", "false").count()).isEqualTo(1);
        assertThat(registry.counter("cobre.notification.settled", "event_type", "credit_transfer",
                "status", "completed", "after_retries", "true").count()).isEqualTo(1);
        assertThat(registry.counter("cobre.notification.settled", "event_type", "credit_transfer",
                "status", "failed", "after_retries", "true").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("cuenta ingestas, reintentos y reenvios")
    void cuenta_el_resto_del_ciclo() {
        adapter.eventIngested("debit_purchase");
        adapter.retryScheduled("debit_purchase", 2);
        adapter.replayRequested("debit_purchase");

        assertThat(registry.counter("cobre.notification.ingested", "event_type", "debit_purchase").count())
                .isEqualTo(1);
        assertThat(registry.counter("cobre.notification.retry.scheduled",
                "event_type", "debit_purchase", "attempt", "2").count()).isEqualTo(1);
        assertThat(registry.counter("cobre.notification.replay.requested",
                "event_type", "debit_purchase").count()).isEqualTo(1);
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

    private double gauge(String status) {
        return registry.get("cobre.notification.backlog").tag("status", status).gauge().value();
    }
}
