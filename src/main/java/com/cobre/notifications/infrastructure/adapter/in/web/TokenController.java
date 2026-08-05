package com.cobre.notifications.infrastructure.adapter.in.web;

import com.cobre.notifications.application.port.in.IssueAccessTokenUseCase;
import com.cobre.notifications.infrastructure.adapter.in.web.dto.TokenRequest;
import com.cobre.notifications.infrastructure.adapter.in.web.dto.TokenResponse;
import com.cobre.notifications.infrastructure.config.ConditionalOnRole;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Emision de tokens de acceso.
 *
 * <p><b>Es POST y no GET, deliberadamente.</b> Un GET llevaria el {@code client_secret}
 * en la URL, y las URLs terminan en los logs de acceso del balanceador, en el historial
 * del navegador, en la cabecera {@code Referer} y en cualquier proxy intermedio. El
 * cuerpo de un POST no.
 *
 * <p>La respuesta se marca como no cacheable: un token en la cache de un proxy
 * compartido es un token entregado a quien pase despues.
 *
 * <p>Es el unico endpoint publico de la API, y por eso es el mas expuesto a fuerza
 * bruta. Su defensa esta repartida: credenciales guardadas como hash, tiempo de
 * respuesta constante para no revelar que clientes existen, un unico mensaje de error
 * para todos los fallos, y limite de intentos por IP en {@code TokenRateLimitWebFilter}.
 */
@RestController
@RequestMapping("/oauth/token")
@ConditionalOnRole(ConditionalOnRole.API)
public class TokenController {

    private final IssueAccessTokenUseCase issueUseCase;

    public TokenController(IssueAccessTokenUseCase issueUseCase) {
        this.issueUseCase = issueUseCase;
    }

    @PostMapping
    public Mono<ResponseEntity<TokenResponse>> issue(@Valid @RequestBody TokenRequest request) {
        return issueUseCase.issue(request.toCredentials())
                .map(token -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(TokenResponse.from(token)));
    }
}
