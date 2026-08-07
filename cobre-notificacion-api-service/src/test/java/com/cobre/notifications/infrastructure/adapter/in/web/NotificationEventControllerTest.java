package com.cobre.notifications.infrastructure.adapter.in.web;

import com.cobre.notifications.application.port.in.GetNotificationEventUseCase;
import com.cobre.notifications.application.port.in.QueryNotificationEventsUseCase;
import com.cobre.notifications.application.port.in.ReplayNotificationEventUseCase;
import com.cobre.notifications.domain.exception.InvalidQueryException;
import com.cobre.notifications.domain.model.AttemptOutcome;
import com.cobre.notifications.domain.model.DeliveryAttempt;
import com.cobre.notifications.domain.model.DeliveryStatus;
import com.cobre.notifications.domain.model.EventQuery;
import com.cobre.notifications.domain.model.NotificationEvent;
import com.cobre.notifications.domain.model.NotificationEventDetail;
import com.cobre.notifications.domain.model.PageResult;
import com.cobre.notifications.domain.model.WebhookDeliveryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationEventControllerTest {

    private static final String CLIENT_ID = "CLIENT002";
    private static final String EVENT_ID = "EVT003";
    private static final Instant NOW = Instant.parse("2024-03-15T11:20:18Z");

    private final QueryNotificationEventsUseCase queryUseCase = mock(QueryNotificationEventsUseCase.class);
    private final GetNotificationEventUseCase getUseCase = mock(GetNotificationEventUseCase.class);
    private final ReplayNotificationEventUseCase replayUseCase = mock(ReplayNotificationEventUseCase.class);

    private final NotificationEventController controller =
            new NotificationEventController(queryUseCase, getUseCase, replayUseCase);

    private static Jwt tokenFor(String clientId) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("api-client")
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(3600));
        if (clientId != null) {
            builder.claim("client_id", clientId);
        }
        return builder.build();
    }

    private static NotificationEvent failedEvent() {
        return NotificationEvent.received(
                        EVENT_ID, CLIENT_ID, "credit_transfer", "Transferencia recibida",
                        NOW.minusSeconds(2), NOW.minusSeconds(2))
                .markFailed(WebhookDeliveryResult.retryable(503, "webhook caido", 5000), NOW);
    }

    @Test
    @DisplayName("el listado se acota siempre al cliente del token")
    void acota_el_listado_al_token() {
        when(queryUseCase.query(any())).thenReturn(Mono.just(
                new PageResult<>(List.of(failedEvent()), 20, "siguiente")));

        StepVerifier.create(controller.list(tokenFor(CLIENT_ID), null, null, null, null, 20))
                .assertNext(page -> {
                    assertThat(page.data()).hasSize(1);
                    assertThat(page.data().get(0).eventId()).isEqualTo(EVENT_ID);
                    assertThat(page.data().get(0).deliveryStatus()).isEqualTo("failed");
                    assertThat(page.nextCursor()).isEqualTo("siguiente");
                    assertThat(page.hasNext()).isTrue();
                })
                .verifyComplete();

        ArgumentCaptor<EventQuery> query = ArgumentCaptor.forClass(EventQuery.class);
        verify(queryUseCase).query(query.capture());
        assertThat(query.getValue().clientId()).isEqualTo(CLIENT_ID);
    }

    @Test
    @DisplayName("traslada los filtros de fecha y estado al caso de uso")
    void traslada_los_filtros() {
        when(queryUseCase.query(any())).thenReturn(Mono.just(new PageResult<>(List.of(), 50, null)));
        Instant from = Instant.parse("2024-03-15T00:00:00Z");
        Instant to = Instant.parse("2024-03-16T00:00:00Z");

        StepVerifier.create(controller.list(tokenFor(CLIENT_ID), from, to, "failed", "abc", 50))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<EventQuery> query = ArgumentCaptor.forClass(EventQuery.class);
        verify(queryUseCase).query(query.capture());
        assertThat(query.getValue().createdFrom()).isEqualTo(from);
        assertThat(query.getValue().createdTo()).isEqualTo(to);
        assertThat(query.getValue().deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(query.getValue().cursor()).isEqualTo("abc");
        assertThat(query.getValue().size()).isEqualTo(50);
    }

    @Test
    @DisplayName("un estado desconocido se rechaza indicando los valores validos")
    void rechaza_estado_desconocido() {
        assertThatThrownBy(() -> controller.list(tokenFor(CLIENT_ID), null, null, "entregado", null, 20))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("completed")
                .hasMessageContaining("failed");
    }

    @Test
    @DisplayName("el detalle incluye la bitacora de intentos y la URL destino")
    void devuelve_el_detalle() {
        DeliveryAttempt attempt = new DeliveryAttempt(
                UUID.randomUUID(), EVENT_ID, 1, 0, NOW,
                AttemptOutcome.RETRYABLE_FAILURE, 503, 5000, "webhook caido");
        when(getUseCase.get(EVENT_ID, CLIENT_ID)).thenReturn(Mono.just(
                new NotificationEventDetail(
                        failedEvent().withWebhookUrl("https://cliente.example.com/hook"),
                        List.of(attempt))));

        StepVerifier.create(controller.get(tokenFor(CLIENT_ID), EVENT_ID))
                .assertNext(detail -> {
                    assertThat(detail.eventId()).isEqualTo(EVENT_ID);
                    assertThat(detail.webhookUrl()).isEqualTo("https://cliente.example.com/hook");
                    assertThat(detail.deliveryAttempts()).hasSize(1);
                    assertThat(detail.deliveryAttempts().get(0).outcome()).isEqualTo("retryable_failure");
                    assertThat(detail.deliveryAttempts().get(0).httpStatus()).isEqualTo(503);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("el reenvio responde el nuevo estado y el numero de ciclo")
    void acusa_el_reenvio() {
        when(replayUseCase.replay(EVENT_ID, CLIENT_ID))
                .thenReturn(Mono.just(failedEvent().preparedForReplay(NOW)));

        StepVerifier.create(controller.replay(tokenFor(CLIENT_ID), EVENT_ID))
                .assertNext(response -> {
                    assertThat(response.eventId()).isEqualTo(EVENT_ID);
                    assertThat(response.deliveryStatus()).isEqualTo("pending");
                    assertThat(response.replayCount()).isEqualTo(1);
                    assertThat(response.message()).contains("encolado");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("un token sin client_id no identifica a nadie y se rechaza")
    void rechaza_token_sin_cliente() {
        assertThatThrownBy(() -> controller.list(tokenFor(null), null, null, null, null, 20))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasMessageContaining("client_id");
    }

    @Test
    @DisplayName("una peticion sin token no llega al caso de uso")
    void rechaza_peticion_sin_token() {
        assertThatThrownBy(() -> controller.get(null, EVENT_ID))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    @DisplayName("un client_id en blanco se trata como ausente")
    void rechaza_client_id_en_blanco() {
        Jwt blank = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claims(claims -> claims.putAll(Map.of("client_id", "   ")))
                .build();

        assertThatThrownBy(() -> controller.replay(blank, EVENT_ID))
                .isInstanceOf(InvalidBearerTokenException.class);
    }
}
