package com.cobre.notifications.infrastructure.security;

import com.cobre.notifications.infrastructure.config.SecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defensa del endpoint de token.
 *
 * <p>Ya no se prueban aqui ni la firma ni la verificacion de secretos: el servicio dejo
 * de emitir y de guardar credenciales, y ambas cosas son ahora responsabilidad del
 * proveedor de identidad. Lo que si sigue siendo nuestro es contener la prueba de
 * secretos a ciegas antes de reenviarla.
 */
class AccessTokenSecurityTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

    private static SecurityProperties properties(int tokenPerMinute) {
        return new SecurityProperties(
                new SecurityProperties.Oidc(
                        "http://localhost:8087/realms/cobre",
                        "http://localhost:8087/realms/cobre/protocol/openid-connect/certs",
                        "http://localhost:8087/realms/cobre/protocol/openid-connect/token"),
                new SecurityProperties.RateLimit(true, 120, tokenPerMinute), false);
    }

    @Nested
    @DisplayName("Limite de intentos contra el endpoint de token")
    class TokenRateLimit {

        private final AtomicInteger llamadasAlSiguiente = new AtomicInteger();
        private final WebFilterChain chain = exchange -> {
            llamadasAlSiguiente.incrementAndGet();
            return Mono.empty();
        };

        private MockServerWebExchange peticionDeToken() {
            return MockServerWebExchange.from(MockServerHttpRequest
                    .post("/oauth/token")
                    .remoteAddress(new InetSocketAddress("203.0.113.7", 44444)));
        }

        @Test
        @DisplayName("deja pasar hasta la cuota y despues responde 429")
        void corta_al_superar_la_cuota() {
            TokenRateLimitWebFilter filter = new TokenRateLimitWebFilter(
                    properties(3), Clock.fixed(NOW, ZoneOffset.UTC));

            for (int i = 0; i < 3; i++) {
                MockServerWebExchange exchange = peticionDeToken();
                StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
                assertThat(exchange.getResponse().getStatusCode()).isNull();
            }

            MockServerWebExchange excedida = peticionDeToken();
            StepVerifier.create(filter.filter(excedida, chain)).verifyComplete();

            assertThat(excedida.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(excedida.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
            assertThat(llamadasAlSiguiente.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("no toca el resto de la API: ahi manda el limite por cliente")
        void solo_limita_el_endpoint_de_token() {
            TokenRateLimitWebFilter filter = new TokenRateLimitWebFilter(
                    properties(1), Clock.fixed(NOW, ZoneOffset.UTC));

            for (int i = 0; i < 5; i++) {
                MockServerWebExchange exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.get("/notification_events")
                                .remoteAddress(new InetSocketAddress("203.0.113.7", 44444)));
                StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
                assertThat(exchange.getResponse().getStatusCode()).isNull();
            }
        }

        @Test
        @DisplayName("desactivado no interfiere con nada")
        void desactivado_no_interfiere() {
            TokenRateLimitWebFilter filter = new TokenRateLimitWebFilter(
                    new SecurityProperties(
                            new SecurityProperties.Oidc("http://localhost:8087/realms/cobre", null, null),
                            new SecurityProperties.RateLimit(false, 120, 1), false),
                    Clock.fixed(NOW, ZoneOffset.UTC));

            for (int i = 0; i < 5; i++) {
                MockServerWebExchange exchange = peticionDeToken();
                StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
                assertThat(exchange.getResponse().getStatusCode()).isNull();
            }
        }

        @Test
        @DisplayName("la cuota es por origen: un atacante no consume la de los demas")
        void la_cuota_es_por_origen() {
            TokenRateLimitWebFilter filter = new TokenRateLimitWebFilter(
                    properties(1), Clock.fixed(NOW, ZoneOffset.UTC));

            StepVerifier.create(filter.filter(peticionDeToken(), chain)).verifyComplete();

            MockServerWebExchange otroOrigen = MockServerWebExchange.from(MockServerHttpRequest
                    .post("/oauth/token")
                    .remoteAddress(new InetSocketAddress("198.51.100.9", 55555)));
            StepVerifier.create(filter.filter(otroOrigen, chain)).verifyComplete();

            assertThat(otroOrigen.getResponse().getStatusCode()).isNull();
        }

        @Test
        @DisplayName("la cuota se renueva al cambiar de minuto")
        void la_cuota_se_renueva_cada_minuto() {
            AvanzableClock clock = new AvanzableClock(NOW);
            TokenRateLimitWebFilter filter = new TokenRateLimitWebFilter(properties(1), clock);

            StepVerifier.create(filter.filter(peticionDeToken(), chain)).verifyComplete();

            MockServerWebExchange excedida = peticionDeToken();
            StepVerifier.create(filter.filter(excedida, chain)).verifyComplete();
            assertThat(excedida.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            clock.avanzar(Duration.ofMinutes(1));

            MockServerWebExchange nuevaVentana = peticionDeToken();
            StepVerifier.create(filter.filter(nuevaVentana, chain)).verifyComplete();
            assertThat(nuevaVentana.getResponse().getStatusCode()).isNull();
        }

        @Test
        @DisplayName("purga las ventanas viejas para que el mapa no crezca sin fin")
        void purga_ventanas_viejas() {
            AvanzableClock clock = new AvanzableClock(NOW);
            TokenRateLimitWebFilter filter = new TokenRateLimitWebFilter(properties(1), clock);
            StepVerifier.create(filter.filter(peticionDeToken(), chain)).verifyComplete();

            // Con la ventana aun vigente, la purga no debe llevarse nada.
            filter.evictStaleWindows();
            MockServerWebExchange mismaVentana = peticionDeToken();
            StepVerifier.create(filter.filter(mismaVentana, chain)).verifyComplete();
            assertThat(mismaVentana.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            // Pasados varios minutos, la ventana ya no cuenta y se libera memoria.
            clock.avanzar(Duration.ofMinutes(5));
            filter.evictStaleWindows();

            MockServerWebExchange despues = peticionDeToken();
            StepVerifier.create(filter.filter(despues, chain)).verifyComplete();
            assertThat(despues.getResponse().getStatusCode()).isNull();
        }
    }

    /** Reloj de prueba que se puede adelantar, para ejercitar ventanas de tiempo. */
    private static final class AvanzableClock extends Clock {

        private Instant instante;

        private AvanzableClock(Instant inicio) {
            this.instante = inicio;
        }

        private void avanzar(Duration cuanto) {
            instante = instante.plus(cuanto);
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
            return instante;
        }
    }
}
