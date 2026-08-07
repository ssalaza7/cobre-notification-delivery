package com.cobre.notifications.infrastructure.adapter.in.web;

import com.cobre.notifications.application.port.in.ManageSubscriptionsUseCase;
import com.cobre.notifications.infrastructure.adapter.in.web.dto.SubscriptionRequest;
import com.cobre.notifications.infrastructure.adapter.in.web.dto.SubscriptionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Registro self-service de webhooks.
 *
 * <p>El cliente administra los suyos con su propio token. El {@code client_id} sale de
 * ahi y nunca del cuerpo: si viniera en el cuerpo, cualquiera podria registrar un webhook
 * a nombre de otro y desviarse sus notificaciones.
 */
@RestController
@RequestMapping("/subscriptions")
public class SubscriptionController {

    private final ManageSubscriptionsUseCase manageUseCase;

    public SubscriptionController(ManageSubscriptionsUseCase manageUseCase) {
        this.manageUseCase = manageUseCase;
    }

    /**
     * Alta o actualizacion. Responde 201 con el secreto de firma incluido.
     *
     * <p>Es la unica respuesta que lo lleva: despues solo se puede rotar, no consultar.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<SubscriptionResponse> register(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SubscriptionRequest request) {
        return manageUseCase.register(
                        AuthenticatedClient.clientIdOf(jwt), request.eventTypeOrWildcard(), request.webhookUrl())
                .map(SubscriptionResponse::withSigningSecret);
    }

    /** Listado sin secretos. */
    @GetMapping
    public Flux<SubscriptionResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return manageUseCase.list(AuthenticatedClient.clientIdOf(jwt))
                .map(SubscriptionResponse::from);
    }
}
