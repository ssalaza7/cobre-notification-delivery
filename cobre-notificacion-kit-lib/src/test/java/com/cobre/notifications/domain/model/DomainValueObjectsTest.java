package com.cobre.notifications.domain.model;

import com.cobre.notifications.domain.exception.InvalidQueryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainValueObjectsTest {

    @Nested
    @DisplayName("DeliveryStatus")
    class DeliveryStatusTest {

        @Test
        @DisplayName("solo los estados cerrados son terminales")
        void distingue_terminales() {
            assertThat(DeliveryStatus.PENDING.isTerminal()).isFalse();
            assertThat(DeliveryStatus.RETRYING.isTerminal()).isFalse();
            assertThat(DeliveryStatus.COMPLETED.isTerminal()).isTrue();
            assertThat(DeliveryStatus.FAILED.isTerminal()).isTrue();
            assertThat(DeliveryStatus.DISCARDED.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("solo un fallo definitivo es reenviable")
        void solo_failed_es_reenviable() {
            assertThat(DeliveryStatus.FAILED.isReplayable()).isTrue();
            assertThat(DeliveryStatus.COMPLETED.isReplayable()).isFalse();
            assertThat(DeliveryStatus.DISCARDED.isReplayable()).isFalse();
            assertThat(DeliveryStatus.PENDING.isReplayable()).isFalse();
        }

        @Test
        @DisplayName("la API habla en minuscula, como el JSON de origen")
        void expone_valores_en_minuscula() {
            assertThat(DeliveryStatus.COMPLETED.apiValue()).isEqualTo("completed");
            assertThat(DeliveryStatus.FAILED.apiValue()).isEqualTo("failed");
        }

        @Test
        @DisplayName("acepta el valor en cualquier caja y trata el vacio como sin filtro")
        void interpreta_valores_de_entrada() {
            assertThat(DeliveryStatus.fromApiValue("completed")).isEqualTo(DeliveryStatus.COMPLETED);
            assertThat(DeliveryStatus.fromApiValue(" FAILED ")).isEqualTo(DeliveryStatus.FAILED);
            assertThat(DeliveryStatus.fromApiValue(null)).isNull();
            assertThat(DeliveryStatus.fromApiValue("  ")).isNull();
        }

        @Test
        @DisplayName("un estado desconocido no se interpreta en silencio")
        void rechaza_valores_desconocidos() {
            assertThatThrownBy(() -> DeliveryStatus.fromApiValue("entregado"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("EventQuery")
    class EventQueryTest {

        @Test
        @DisplayName("la primera pagina es la que llega sin cursor")
        void detecta_primera_pagina() {
            assertThat(new EventQuery("CLIENT001", null, null, null, null, 20).isFirstPage()).isTrue();
            assertThat(new EventQuery("CLIENT001", null, null, null, "abc", 20).isFirstPage()).isFalse();
        }

        @Test
        @DisplayName("un cursor en blanco se trata como ausente, no como cursor invalido")
        void cursor_en_blanco_equivale_a_ausente() {
            assertThat(new EventQuery("CLIENT001", null, null, null, "   ", 20).cursor()).isNull();
        }

        @Test
        @DisplayName("no se puede consultar sin cliente: es lo que impide leer datos ajenos")
        void exige_cliente() {
            assertThatThrownBy(() -> new EventQuery(null, null, null, null, null, 20))
                    .isInstanceOf(InvalidQueryException.class)
                    .hasMessageContaining("client_id");
        }

        @Test
        @DisplayName("acota el tamano de pagina para que nadie pida la tabla entera")
        void acota_el_tamano() {
            assertThatThrownBy(() -> new EventQuery("CLIENT001", null, null, null, null, 0))
                    .isInstanceOf(InvalidQueryException.class);

            assertThatThrownBy(() -> new EventQuery(
                    "CLIENT001", null, null, null, null, EventQuery.MAX_PAGE_SIZE + 1))
                    .isInstanceOf(InvalidQueryException.class)
                    .hasMessageContaining("size");
        }

        @Test
        @DisplayName("rechaza rangos de fecha invertidos")
        void valida_rango() {
            assertThatThrownBy(() -> new EventQuery(
                    "CLIENT001",
                    Instant.parse("2024-03-16T00:00:00Z"),
                    Instant.parse("2024-03-15T00:00:00Z"),
                    null, null, 20))
                    .isInstanceOf(InvalidQueryException.class)
                    .hasMessageContaining("created_from");
        }
    }

    @Nested
    @DisplayName("PageResult")
    class PageResultTest {

        @Test
        @DisplayName("con cursor de continuacion queda pagina siguiente")
        void anuncia_siguiente() {
            PageResult<String> page = new PageResult<>(List.of("a", "b"), 2, "cursor");

            assertThat(page.items()).hasSize(2);
            assertThat(page.hasNext()).isTrue();
        }

        @Test
        @DisplayName("la ultima pagina no anuncia una siguiente")
        void detecta_ultima_pagina() {
            PageResult<String> page = new PageResult<>(List.of("e"), 2, null);

            assertThat(page.hasNext()).isFalse();
        }

        @Test
        @DisplayName("sin resultados no hay pagina siguiente")
        void pagina_vacia() {
            PageResult<String> page = new PageResult<>(List.of(), 20, null);

            assertThat(page.items()).isEmpty();
            assertThat(page.hasNext()).isFalse();
        }
    }

    @Nested
    @DisplayName("Subscription")
    class SubscriptionTest {

        private Subscription subscription(String eventType, boolean active) {
            return new Subscription(
                    UUID.randomUUID(), "CLIENT001", eventType,
                    "https://cliente.example.com/hook", "whsec_test", active);
        }

        @Test
        @DisplayName("el comodin cubre cualquier tipo de evento")
        void el_comodin_cubre_todo() {
            Subscription wildcard = subscription(Subscription.ALL_EVENT_TYPES, true);

            assertThat(wildcard.covers("credit_transfer")).isTrue();
            assertThat(wildcard.covers("debit_purchase")).isTrue();
        }

        @Test
        @DisplayName("una suscripcion especifica solo cubre su propio tipo")
        void la_especifica_solo_cubre_lo_suyo() {
            Subscription specific = subscription("credit_transfer", true);

            assertThat(specific.covers("credit_transfer")).isTrue();
            assertThat(specific.covers("debit_purchase")).isFalse();
        }

        @Test
        @DisplayName("una suscripcion inactiva o de otro cliente no entrega")
        void no_entrega_si_no_corresponde() {
            assertThat(subscription("*", true).deliversTo("CLIENT001")).isTrue();
            assertThat(subscription("*", false).deliversTo("CLIENT001")).isFalse();
            assertThat(subscription("*", true).deliversTo("CLIENT002")).isFalse();
        }

        @Test
        @DisplayName("cambiar la URL conserva el resto de la suscripcion")
        void sustituye_la_url() {
            Subscription original = subscription("*", true);

            Subscription moved = original.withWebhookUrl("https://demo.example.com/hook");

            assertThat(moved.webhookUrl()).isEqualTo("https://demo.example.com/hook");
            assertThat(moved.id()).isEqualTo(original.id());
            assertThat(moved.signingSecret()).isEqualTo(original.signingSecret());
        }

        @Test
        @DisplayName("no se construye una suscripcion sin destino ni secreto")
        void valida_campos_obligatorios() {
            assertThatThrownBy(() -> new Subscription(
                    UUID.randomUUID(), "CLIENT001", "*", "", "secreto", true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("webhookUrl");
        }
    }

    @Nested
    @DisplayName("WebhookDeliveryResult y DeliveryAttempt")
    class DeliveryResultTest {

        @Test
        @DisplayName("las fabricas fijan el desenlace correcto")
        void clasifica_resultados() {
            assertThat(WebhookDeliveryResult.delivered(200, 10).isDelivered()).isTrue();
            assertThat(WebhookDeliveryResult.retryable(503, "x", 10).outcome())
                    .isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
            assertThat(WebhookDeliveryResult.permanent(400, "x", 10).outcome())
                    .isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
            assertThat(AttemptOutcome.DELIVERED.isFailure()).isFalse();
            assertThat(AttemptOutcome.PERMANENT_FAILURE.isFailure()).isTrue();
        }

        @Test
        @DisplayName("una duracion negativa no tiene sentido")
        void rechaza_duracion_negativa() {
            assertThatThrownBy(() -> WebhookDeliveryResult.delivered(200, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("el intento hereda los datos del resultado")
        void construye_intento_desde_resultado() {
            Instant now = Instant.parse("2024-03-15T09:30:22Z");
            WebhookDeliveryResult result = WebhookDeliveryResult.retryable(503, "no disponible", 4200);

            DeliveryAttempt attempt = DeliveryAttempt.of("EVT001", 2, 1, now, result);

            assertThat(attempt.eventId()).isEqualTo("EVT001");
            assertThat(attempt.attemptNumber()).isEqualTo(2);
            assertThat(attempt.replayCount()).isEqualTo(1);
            assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
            assertThat(attempt.httpStatus()).isEqualTo(503);
            assertThat(attempt.durationMs()).isEqualTo(4200);
            assertThat(attempt.id()).isNotNull();
        }

        @Test
        @DisplayName("no existe el intento numero cero")
        void rechaza_numero_de_intento_invalido() {
            assertThatThrownBy(() -> DeliveryAttempt.of(
                    "EVT001", 0, 0, Instant.now(), WebhookDeliveryResult.delivered(200, 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("attemptNumber");
        }
    }

    @Nested
    @DisplayName("WebhookDeliveryRequest")
    class WebhookDeliveryRequestTest {

        @Test
        @DisplayName("toma la URL y el secreto de la suscripcion, no del evento")
        void se_arma_desde_la_suscripcion() {
            NotificationEvent event = NotificationEvent.received(
                    "EVT001", "CLIENT001", "credit_transfer", "contenido",
                    Instant.parse("2024-03-15T09:30:20Z"), Instant.parse("2024-03-15T09:30:20Z"));
            Subscription subscription = new Subscription(
                    UUID.randomUUID(), "CLIENT001", "*",
                    "https://cliente.example.com/hook", "whsec_test", true);

            WebhookDeliveryRequest request = WebhookDeliveryRequest.of(event, subscription, 3);

            assertThat(request.targetUrl()).isEqualTo("https://cliente.example.com/hook");
            assertThat(request.signingSecret()).isEqualTo("whsec_test");
            assertThat(request.attemptNumber()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("NotificationEventDetail")
    class NotificationEventDetailTest {

        @Test
        @DisplayName("la lista de intentos queda inmutable")
        void protege_la_lista() {
            NotificationEvent event = NotificationEvent.received(
                    "EVT001", "CLIENT001", "tipo", "contenido", Instant.now(), Instant.now());

            NotificationEventDetail detail = new NotificationEventDetail(event, List.of());

            assertThatThrownBy(() -> detail.attempts().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
