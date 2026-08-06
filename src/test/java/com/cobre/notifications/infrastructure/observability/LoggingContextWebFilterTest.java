package com.cobre.notifications.infrastructure.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.ContextView;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingContextWebFilterTest {

    private final LoggingContextWebFilter filter = new LoggingContextWebFilter();
    private final AtomicReference<ContextView> captured = new AtomicReference<>();

    /** Cadena que no hace nada salvo capturar el contexto reactivo que le llega. */
    private final WebFilterChain capturingChain = exchange ->
            Mono.deferContextual(context -> {
                captured.set(context);
                return Mono.empty();
            });

    private static Jwt jwtFor(String clientId) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("client_id", clientId)
                .claim("sub", clientId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
    }

    private static Authentication authenticatedWith(Jwt jwt) {
        return new TestingAuthenticationToken(jwt, "n/a", "SCOPE_notifications:read");
    }

    private MockServerWebExchange exchangeWith(MockServerHttpRequest request) {
        return MockServerWebExchange.from(request);
    }

    /** ContextView.get devuelve un generico; se fija el tipo para poder aseverar. */
    private String contextValue(String key) {
        return captured.get().get(key);
    }

    @Test
    @DisplayName("genera un identificador de peticion y lo devuelve al cliente")
    void genera_identificador_de_peticion() {
        MockServerWebExchange exchange = exchangeWith(MockServerHttpRequest.get("/notification_events").build());

        StepVerifier.create(filter.filter(exchange, capturingChain)).verifyComplete();

        String header = exchange.getResponse().getHeaders()
                .getFirst(LoggingContextWebFilter.REQUEST_ID_HEADER);
        assertThat(header).isNotBlank();
        assertThat(contextValue(LogFields.REQUEST_ID)).isEqualTo(header);
    }

    @Test
    @DisplayName("respeta el identificador que viene del borde para no romper la traza")
    void respeta_el_identificador_entrante() {
        MockServerWebExchange exchange = exchangeWith(MockServerHttpRequest
                .get("/notification_events")
                .header(LoggingContextWebFilter.REQUEST_ID_HEADER, "traza-del-gateway-123")
                .build());

        StepVerifier.create(filter.filter(exchange, capturingChain)).verifyComplete();

        assertThat(contextValue(LogFields.REQUEST_ID)).isEqualTo("traza-del-gateway-123");
        assertThat(exchange.getResponse().getHeaders().getFirst(LoggingContextWebFilter.REQUEST_ID_HEADER))
                .isEqualTo("traza-del-gateway-123");
    }

    @Test
    @DisplayName("acota un identificador desmedido: termina en un campo indexado")
    void acota_el_identificador_entrante() {
        MockServerWebExchange exchange = exchangeWith(MockServerHttpRequest
                .get("/notification_events")
                .header(LoggingContextWebFilter.REQUEST_ID_HEADER, "x".repeat(5000))
                .build());

        StepVerifier.create(filter.filter(exchange, capturingChain)).verifyComplete();

        assertThat(contextValue(LogFields.REQUEST_ID)).hasSize(64);
    }

    @Test
    @DisplayName("un identificador en blanco se trata como ausente")
    void ignora_un_identificador_en_blanco() {
        MockServerWebExchange exchange = exchangeWith(MockServerHttpRequest
                .get("/notification_events")
                .header(LoggingContextWebFilter.REQUEST_ID_HEADER, "   ")
                .build());

        StepVerifier.create(filter.filter(exchange, capturingChain)).verifyComplete();

        assertThat(contextValue(LogFields.REQUEST_ID)).isNotBlank().doesNotContain(" ");
    }

    @Test
    @DisplayName("con un token autenticado, el cliente queda en el contexto de logging")
    void propaga_el_cliente_autenticado() {
        MockServerWebExchange exchange = exchangeWith(MockServerHttpRequest.get("/notification_events").build());

        StepVerifier.create(filter.filter(exchange, capturingChain)
                        .contextWrite(ReactiveSecurityContextHolder
                                .withAuthentication(authenticatedWith(jwtFor("CLIENT002")))))
                .verifyComplete();

        assertThat(contextValue(LogFields.CLIENT_ID)).isEqualTo("CLIENT002");
    }

    @Test
    @DisplayName("sin autenticacion la peticion sigue, solo que sin cliente en el contexto")
    void sin_autenticacion_no_falla() {
        MockServerWebExchange exchange = exchangeWith(MockServerHttpRequest.get("/actuator/health").build());

        StepVerifier.create(filter.filter(exchange, capturingChain)).verifyComplete();

        assertThat(captured.get().hasKey(LogFields.CLIENT_ID)).isFalse();
        assertThat(captured.get().hasKey(LogFields.REQUEST_ID)).isTrue();
    }

    @Test
    @DisplayName("un principal que no es un token no aporta cliente, y tampoco rompe")
    void tolera_un_principal_distinto() {
        MockServerWebExchange exchange = exchangeWith(MockServerHttpRequest.get("/notification_events").build());

        StepVerifier.create(filter.filter(exchange, capturingChain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                                new TestingAuthenticationToken("un-usuario", "n/a", "SCOPE_x"))))
                .verifyComplete();

        assertThat(captured.get().hasKey(LogFields.CLIENT_ID)).isFalse();
    }

    @Test
    @DisplayName("un token sin el claim de cliente no inventa uno")
    void un_token_sin_claim_no_aporta_cliente() {
        Jwt sinClaim = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("sub", "alguien")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        MockServerWebExchange exchange = exchangeWith(MockServerHttpRequest.get("/notification_events").build());

        StepVerifier.create(filter.filter(exchange, capturingChain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authenticatedWith(sinClaim))))
                .verifyComplete();

        assertThat(captured.get().hasKey(LogFields.CLIENT_ID)).isFalse();
    }

    @Test
    @DisplayName("los campos de correlacion son pocos y estables: cada uno cuesta en el indice")
    void los_campos_son_pocos_y_estables() {
        assertThat(LogFields.ALL)
                .containsExactly(LogFields.REQUEST_ID, LogFields.CLIENT_ID,
                        LogFields.EVENT_ID, LogFields.LOG_TYPE);
        assertThat(Map.of(
                LogFields.REQUEST_ID, "request_id",
                LogFields.CLIENT_ID, "client_id",
                LogFields.EVENT_ID, "event_id",
                LogFields.LOG_TYPE, "log_type"))
                .allSatisfy((actual, expected) -> assertThat(actual).isEqualTo(expected));
    }
}
