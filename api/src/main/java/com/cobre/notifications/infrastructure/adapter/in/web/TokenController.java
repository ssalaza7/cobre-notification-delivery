package com.cobre.notifications.infrastructure.adapter.in.web;

import com.cobre.notifications.application.port.in.IssueAccessTokenUseCase;
import com.cobre.notifications.infrastructure.adapter.in.web.dto.TokenRequest;
import com.cobre.notifications.infrastructure.adapter.in.web.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/**
 * Emision de tokens de acceso.
 *
 * <p><b>Es POST y no GET, deliberadamente.</b> Un GET llevaria el {@code client_secret}
 * en la URL, y las URLs terminan en los logs de acceso del balanceador, en el historial
 * del navegador, en la cabecera {@code Referer} y en cualquier proxy intermedio. El
 * cuerpo de un POST no. Es ademas lo que exige el RFC 6749 para
 * {@code client_credentials}.
 *
 * <p>Acepta los dos formatos de cuerpo: {@code application/x-www-form-urlencoded}, que es
 * el del estandar y el que usan las pasarelas de pago del mercado, y {@code application/json}
 * por comodidad de quien integra desde un cliente HTTP moderno.
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
public class TokenController {

    private final IssueAccessTokenUseCase issueUseCase;

    public TokenController(IssueAccessTokenUseCase issueUseCase) {
        this.issueUseCase = issueUseCase;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<TokenResponse>> issue(@Valid @RequestBody TokenRequest request) {
        return respond(request);
    }

    /** La forma del estandar: {@code grant_type}, {@code client_id} y {@code client_secret} como campos de formulario. */
    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Mono<ResponseEntity<TokenResponse>> issueForm(ServerWebExchange exchange) {
        return exchange.getFormData()
                .map(form -> new TokenRequest(
                        form.getFirst("grant_type"),
                        form.getFirst("client_id"),
                        form.getFirst("client_secret")))
                // El cuerpo de formulario no pasa por @Valid, asi que se comprueba aqui:
                // de lo contrario un campo ausente llegaria como null al caso de uso.
                .doOnNext(TokenController::requireComplete)
                .flatMap(this::respond);
    }

    private static void requireComplete(TokenRequest request) {
        if (isBlank(request.clientId()) || isBlank(request.clientSecret())) {
            throw new ServerWebInputException("client_id y client_secret son obligatorios");
        }
        if (!"client_credentials".equals(request.grantType())) {
            throw new ServerWebInputException("grant_type soportado: client_credentials");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Mono<ResponseEntity<TokenResponse>> respond(TokenRequest request) {
        return issueUseCase.issue(request.toCredentials())
                .map(token -> ResponseEntity.ok()
                        // Un token en la cache de un proxy compartido es un token
                        // entregado a quien pase despues.
                        .cacheControl(CacheControl.noStore())
                        .body(TokenResponse.from(token)));
    }
}
