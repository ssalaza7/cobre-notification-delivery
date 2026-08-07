package com.cobre.notifications.domain.model;

import com.cobre.notifications.domain.exception.InvalidNotificationEventException;
import com.cobre.notifications.domain.exception.ReplayNotAllowedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationEventTest {

    private static final Instant CREATED_AT = Instant.parse("2024-03-15T09:30:20Z");
    private static final Instant NOW = Instant.parse("2024-03-15T09:30:22Z");

    private static NotificationEvent pending() {
        return NotificationEvent.received(
                "EVT001", "CLIENT001", "credit_card_payment", "Pago recibido", CREATED_AT, NOW);
    }

    @Test
    @DisplayName("un evento recien recibido nace pendiente y sin intentos")
    void nace_pendiente() {
        NotificationEvent event = pending();

        assertThat(event.deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(event.attempts()).isZero();
        assertThat(event.replayCount()).isZero();
        assertThat(event.deliveryDate()).isNull();
        assertThat(event.nextAttemptNumber()).isEqualTo(1);
        assertThat(event.version()).isEqualTo(new EventVersion(0, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("rechaza identificadores vacios o en blanco")
    void rechaza_campos_vacios(String blank) {
        assertThatThrownBy(() -> NotificationEvent.received(
                blank, "CLIENT001", "tipo", "contenido", CREATED_AT, NOW))
                .isInstanceOf(InvalidNotificationEventException.class)
                .hasMessageContaining("event_id");
    }

    @Test
    @DisplayName("rechaza un evento sin fecha de creacion")
    void rechaza_sin_fecha() {
        assertThatThrownBy(() -> NotificationEvent.received(
                "EVT001", "CLIENT001", "tipo", "contenido", null, NOW))
                .isInstanceOf(InvalidNotificationEventException.class)
                .hasMessageContaining("created_at");
    }

    @Test
    @DisplayName("una entrega exitosa cierra el evento y registra la fecha")
    void marca_entregado() {
        NotificationEvent delivered = pending().markDelivered(WebhookDeliveryResult.delivered(200, 120), NOW);

        assertThat(delivered.deliveryStatus()).isEqualTo(DeliveryStatus.COMPLETED);
        assertThat(delivered.deliveryStatus().isTerminal()).isTrue();
        assertThat(delivered.deliveryDate()).isEqualTo(NOW);
        assertThat(delivered.attempts()).isEqualTo(1);
        assertThat(delivered.lastHttpStatus()).isEqualTo(200);
        assertThat(delivered.lastError()).isNull();
    }

    @Test
    @DisplayName("un fallo transitorio deja el evento en reintento y sin fecha de cierre")
    void marca_reintentando() {
        NotificationEvent retrying = pending()
                .markRetrying(WebhookDeliveryResult.retryable(503, "no disponible", 5000), NOW);

        assertThat(retrying.deliveryStatus()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(retrying.deliveryStatus().isTerminal()).isFalse();
        assertThat(retrying.deliveryDate()).isNull();
        assertThat(retrying.attempts()).isEqualTo(1);
        assertThat(retrying.nextAttemptNumber()).isEqualTo(2);
        assertThat(retrying.lastError()).isEqualTo("no disponible");
    }

    @Test
    @DisplayName("un fallo definitivo cierra el evento y lo deja reenviable")
    void marca_fallido() {
        NotificationEvent failed = pending()
                .markFailed(WebhookDeliveryResult.retryable(500, "error", 5000), NOW);

        assertThat(failed.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(failed.deliveryStatus().isReplayable()).isTrue();
        assertThat(failed.deliveryDate()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("descartar no consume un intento porque nunca se invoco ningun destino")
    void marca_descartado() {
        NotificationEvent discarded = pending().markDiscarded("sin suscripcion", NOW);

        assertThat(discarded.deliveryStatus()).isEqualTo(DeliveryStatus.DISCARDED);
        assertThat(discarded.attempts()).isZero();
        assertThat(discarded.lastError()).isEqualTo("sin suscripcion");
        assertThat(discarded.deliveryStatus().isReplayable()).isFalse();
    }

    @Test
    @DisplayName("el reenvio reinicia el backoff y cuenta como un ciclo nuevo")
    void prepara_reenvio() {
        NotificationEvent failed = pending()
                .markRetrying(WebhookDeliveryResult.retryable(503, "error", 10), NOW)
                .markFailed(WebhookDeliveryResult.retryable(503, "error", 10), NOW);

        NotificationEvent replayed = failed.preparedForReplay(NOW);

        assertThat(replayed.deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(replayed.attempts()).isZero();
        assertThat(replayed.replayCount()).isEqualTo(1);
        assertThat(replayed.lastError()).isNull();
        assertThat(replayed.lastHttpStatus()).isNull();
        assertThat(replayed.version()).isEqualTo(new EventVersion(0, 1));
    }

    @Test
    @DisplayName("no se puede reenviar una notificacion que ya fue entregada")
    void rechaza_reenvio_de_entregada() {
        NotificationEvent delivered = pending().markDelivered(WebhookDeliveryResult.delivered(200, 10), NOW);

        assertThatThrownBy(() -> delivered.preparedForReplay(NOW))
                .isInstanceOf(ReplayNotAllowedException.class)
                .hasMessageContaining("completed");
    }

    @Test
    @DisplayName("no se puede reenviar una notificacion todavia en curso")
    void rechaza_reenvio_en_curso() {
        assertThatThrownBy(() -> pending().preparedForReplay(NOW))
                .isInstanceOf(ReplayNotAllowedException.class);
    }

    @Test
    @DisplayName("el mensaje de error se trunca para no desbordar la columna")
    void trunca_error_largo() {
        String largo = "x".repeat(DeliveryAttempt.MAX_ERROR_LENGTH + 200);

        NotificationEvent failed = pending()
                .markFailed(WebhookDeliveryResult.retryable(500, largo, 10), NOW);

        assertThat(failed.lastError()).hasSize(DeliveryAttempt.MAX_ERROR_LENGTH);
    }

    @Test
    @DisplayName("la pertenencia al cliente se evalua sobre el propio evento")
    void verifica_pertenencia() {
        NotificationEvent event = pending();

        assertThat(event.belongsTo("CLIENT001")).isTrue();
        assertThat(event.belongsTo("CLIENT002")).isFalse();
    }

    @Test
    @DisplayName("asignar la URL destino no altera el resto del estado")
    void asigna_url() {
        NotificationEvent withUrl = pending().withWebhookUrl("https://cliente.example.com/hook");

        assertThat(withUrl.webhookUrl()).isEqualTo("https://cliente.example.com/hook");
        assertThat(withUrl.deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(withUrl.attempts()).isZero();
    }
}
