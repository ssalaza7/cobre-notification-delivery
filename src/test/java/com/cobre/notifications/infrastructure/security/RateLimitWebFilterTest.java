package com.cobre.notifications.infrastructure.security;

import com.cobre.notifications.infrastructure.config.SecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitWebFilterTest {

    private static final Instant NOW = Instant.parse("2024-03-15T12:00:00Z");

    private final AtomicInteger downstreamCalls = new AtomicInteger();
    private final WebFilterChain chain = exchange -> {
        downstreamCalls.incrementAndGet();
        return Mono.empty();
    };

    private RateLimitWebFilter filter(boolean enabled, int perMinute, Clock clock) {
        return new RateLimitWebFilter(
                new SecurityProperties(
                        new SecurityProperties.Jwt("secreto-de-pruebas-de-al-menos-32-bytes"),
                        new SecurityProperties.RateLimit(enabled, perMinute)),
                clock);
    }

    private ServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/notification_events").build());
    }

    private Authentication jwtFor(String clientId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("client_id", clientId)
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    private Mono<Void> callAs(RateLimitWebFilter filter, Authentication authentication) {
        return filter.filter(exchange(), chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    @Test
    @DisplayName("deja pasar mientras el cliente esta dentro de su cuota")
    void deja_pasar_dentro_de_la_cuota() {
        RateLimitWebFilter filter = filter(true, 3, Clock.fixed(NOW, ZoneOffset.UTC));

        for (int i = 0; i < 3; i++) {
            StepVerifier.create(callAs(filter, jwtFor("CLIENT001"))).verifyComplete();
        }

        assertThat(downstreamCalls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("al superar la cuota responde 429 con Retry-After y no llega al controlador")
    void corta_al_superar_la_cuota() {
        RateLimitWebFilter filter = filter(true, 2, Clock.fixed(NOW, ZoneOffset.UTC));
        ServerWebExchange blocked = exchange();

        StepVerifier.create(callAs(filter, jwtFor("CLIENT001"))).verifyComplete();
        StepVerifier.create(callAs(filter, jwtFor("CLIENT001"))).verifyComplete();
        StepVerifier.create(filter.filter(blocked, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtFor("CLIENT001"))))
                .verifyComplete();

        assertThat(downstreamCalls.get()).isEqualTo(2);
        assertThat(blocked.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("la cuota es por cliente: uno abusivo no consume la de los demas")
    void la_cuota_es_por_cliente() {
        RateLimitWebFilter filter = filter(true, 1, Clock.fixed(NOW, ZoneOffset.UTC));

        StepVerifier.create(callAs(filter, jwtFor("CLIENT001"))).verifyComplete();
        StepVerifier.create(callAs(filter, jwtFor("CLIENT002"))).verifyComplete();

        assertThat(downstreamCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("la cuota se renueva al cambiar de minuto")
    void la_cuota_se_renueva() {
        MutableClock clock = new MutableClock(NOW);
        RateLimitWebFilter filter = filter(true, 1, clock);

        StepVerifier.create(callAs(filter, jwtFor("CLIENT001"))).verifyComplete();
        clock.advance(Duration.ofMinutes(1));
        StepVerifier.create(callAs(filter, jwtFor("CLIENT001"))).verifyComplete();

        assertThat(downstreamCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("si esta desactivado no cuenta nada")
    void puede_desactivarse() {
        RateLimitWebFilter filter = filter(false, 1, Clock.fixed(NOW, ZoneOffset.UTC));

        StepVerifier.create(callAs(filter, jwtFor("CLIENT001"))).verifyComplete();
        StepVerifier.create(callAs(filter, jwtFor("CLIENT001"))).verifyComplete();

        assertThat(downstreamCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("sin contexto de seguridad no hay a quien limitar y la peticion pasa")
    void deja_pasar_sin_autenticacion() {
        RateLimitWebFilter filter = filter(true, 1, Clock.fixed(NOW, ZoneOffset.UTC));

        StepVerifier.create(filter.filter(exchange(), chain)).verifyComplete();

        assertThat(downstreamCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("una autenticacion sin claim client_id se contabiliza por su nombre")
    void usa_el_nombre_si_no_hay_claim() {
        RateLimitWebFilter filter = filter(true, 1, Clock.fixed(NOW, ZoneOffset.UTC));
        Authentication other = new TestingAuthenticationToken("servicio-interno", "n/a");
        ServerWebExchange blocked = exchange();

        StepVerifier.create(callAs(filter, other)).verifyComplete();
        StepVerifier.create(filter.filter(blocked, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(other)))
                .verifyComplete();

        assertThat(downstreamCalls.get()).isEqualTo(1);
        assertThat(blocked.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("la purga libera las ventanas que ya no cuentan")
    void purga_ventanas_viejas() {
        MutableClock clock = new MutableClock(NOW);
        RateLimitWebFilter filter = filter(true, 1, clock);

        StepVerifier.create(callAs(filter, jwtFor("CLIENT001"))).verifyComplete();
        clock.advance(Duration.ofMinutes(10));
        filter.evictStaleWindows();

        // Tras la purga, el mismo cliente vuelve a tener su cuota completa.
        StepVerifier.create(callAs(filter, jwtFor("CLIENT001"))).verifyComplete();
        assertThat(downstreamCalls.get()).isEqualTo(2);
    }

    /** Reloj que avanza a voluntad, para probar el cambio de ventana sin esperar un minuto real. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
