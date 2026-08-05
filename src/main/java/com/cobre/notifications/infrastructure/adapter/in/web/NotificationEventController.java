package com.cobre.notifications.infrastructure.adapter.in.web;

import com.cobre.notifications.application.port.in.GetNotificationEventUseCase;
import com.cobre.notifications.application.port.in.QueryNotificationEventsUseCase;
import com.cobre.notifications.application.port.in.ReplayNotificationEventUseCase;
import com.cobre.notifications.domain.exception.InvalidQueryException;
import com.cobre.notifications.domain.model.DeliveryStatus;
import com.cobre.notifications.domain.model.EventQuery;
import com.cobre.notifications.infrastructure.adapter.in.web.dto.NotificationEventDetailResponse;
import com.cobre.notifications.infrastructure.adapter.in.web.dto.NotificationEventResponse;
import com.cobre.notifications.infrastructure.adapter.in.web.dto.PagedResponse;
import com.cobre.notifications.infrastructure.adapter.in.web.dto.ReplayResponse;
import com.cobre.notifications.infrastructure.config.ConditionalOnRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * API self-service de notificaciones.
 *
 * <p>El {@code client_id} sale siempre del token, nunca de la ruta ni de la query.
 * Es la decision que sostiene el aislamiento entre clientes: no existe forma de pedir
 * los datos de otro tenant porque no hay ningun parametro que lo permita expresar.
 */
@RestController
@RequestMapping("/notification_events")
@ConditionalOnRole(ConditionalOnRole.API)
public class NotificationEventController {

    private final QueryNotificationEventsUseCase queryUseCase;
    private final GetNotificationEventUseCase getUseCase;
    private final ReplayNotificationEventUseCase replayUseCase;

    public NotificationEventController(
            QueryNotificationEventsUseCase queryUseCase,
            GetNotificationEventUseCase getUseCase,
            ReplayNotificationEventUseCase replayUseCase) {
        this.queryUseCase = queryUseCase;
        this.getUseCase = getUseCase;
        this.replayUseCase = replayUseCase;
    }

    /**
     * Listado paginado con filtro por fecha de creacion del evento y por estado de
     * entrega.
     */
    @GetMapping
    public Mono<PagedResponse<NotificationEventResponse>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "created_from", required = false) Instant createdFrom,
            @RequestParam(name = "created_to", required = false) Instant createdTo,
            @RequestParam(name = "delivery_status", required = false) String deliveryStatus,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {

        EventQuery query = new EventQuery(
                AuthenticatedClient.clientIdOf(jwt),
                createdFrom,
                createdTo,
                parseStatus(deliveryStatus),
                page,
                size);

        return queryUseCase.query(query)
                .map(result -> PagedResponse.from(result, NotificationEventResponse::from));
    }

    /** Detalle de una notificacion, con toda su bitacora de intentos. */
    @GetMapping("/{notification_event_id}")
    public Mono<NotificationEventDetailResponse> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("notification_event_id") String notificationEventId) {

        return getUseCase.get(notificationEventId, AuthenticatedClient.clientIdOf(jwt))
                .map(NotificationEventDetailResponse::from);
    }

    /**
     * Reenvia una notificacion cuya entrega fallo definitivamente.
     *
     * <p>202 y no 200: la respuesta confirma que el reenvio quedo encolado, no que el
     * webhook del cliente ya respondio.
     */
    @PostMapping("/{notification_event_id}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<ReplayResponse> replay(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("notification_event_id") String notificationEventId) {

        return replayUseCase.replay(notificationEventId, AuthenticatedClient.clientIdOf(jwt))
                .map(ReplayResponse::from);
    }

    private DeliveryStatus parseStatus(String raw) {
        try {
            return DeliveryStatus.fromApiValue(raw);
        } catch (IllegalArgumentException e) {
            String supported = Arrays.stream(DeliveryStatus.values())
                    .map(DeliveryStatus::apiValue)
                    .collect(Collectors.joining(", "));
            throw new InvalidQueryException(
                    "delivery_status '" + raw + "' no es valido; valores soportados: " + supported);
        }
    }
}
