package com.cobre.notifications.infrastructure.security;

import com.cobre.notifications.domain.model.AccessToken;
import com.cobre.notifications.domain.model.ApiCredential;
import com.cobre.notifications.infrastructure.config.SecurityProperties;
import com.nimbusds.jwt.SignedJWT;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenSecurityTest {

    private static final String SECRET = "secreto-de-pruebas-de-al-menos-32-bytes-largo";
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

    private static SecurityProperties properties(int tokenPerMinute) {
        return new SecurityProperties(
                new SecurityProperties.Jwt(SECRET, Duration.ofMinutes(30), "emisor-de-pruebas"),
                new SecurityProperties.RateLimit(true, 120, tokenPerMinute));
    }

    @Nested
    @DisplayName("Emision del token")
    class Issuer {

        private final NimbusAccessTokenIssuer issuer =
                new NimbusAccessTokenIssuer(properties(10), Clock.fixed(NOW, ZoneOffset.UTC));

        private final ApiCredential credential = new ApiCredential(
                "CLIENT002", "$2a$10$hash", List.of("notifications:read", "notifications:replay"), true);

        @Test
        @DisplayName("firma un JWT con el cliente y sus permisos")
        void firma_el_token() throws Exception {
            AccessToken token = issuer.issue(credential);

            SignedJWT jwt = SignedJWT.parse(token.value());
            assertThat(jwt.getJWTClaimsSet().getStringClaim("client_id")).isEqualTo("CLIENT002");
            assertThat(jwt.getJWTClaimsSet().getStringClaim("scope"))
                    .isEqualTo("notifications:read notifications:replay");
            assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("emisor-de-pruebas");
            assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
        }

        @Test
        @DisplayName("la vigencia es corta y coincide con la configurada")
        void la_vigencia_es_la_configurada() throws Exception {
            AccessToken token = issuer.issue(credential);

            assertThat(token.expiresInSeconds()).isEqualTo(1800);
            SignedJWT jwt = SignedJWT.parse(token.value());
            assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
                    .isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        }

        @Test
        @DisplayName("cada token lleva un identificador unico, base de una futura revocacion")
        void cada_token_es_unico() throws Exception {
            String primero = SignedJWT.parse(issuer.issue(credential).value())
                    .getJWTClaimsSet().getJWTID();
            String segundo = SignedJWT.parse(issuer.issue(credential).value())
                    .getJWTClaimsSet().getJWTID();

            assertThat(primero).isNotNull().isNotEqualTo(segundo);
        }

        @Test
        @DisplayName("una vigencia no positiva no se puede representar")
        void rechaza_vigencia_invalida() {
            assertThat(properties(10).jwt().tokenTtl()).isPositive();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> new AccessToken("t", Duration.ZERO, "scope"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Verificacion de secretos")
    class Hasher {

        private final BCryptSecretHasherAdapter hasher = new BCryptSecretHasherAdapter();

        /** bcrypt de "demo-secret-client002", generado con coste 10. */
        private static final String HASH =
                "$2a$10$7EqJtq98hPqEX7fNZaFWoO0nJ5gTGYzD6q0hHYQ0z6r0kFvKZKQ8W";

        @Test
        @DisplayName("un secreto correcto coincide con su hash")
        void acepta_el_secreto_correcto() {
            String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                    .encode("demo-secret-client002");

            StepVerifier.create(hasher.matches("demo-secret-client002", hash))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("un secreto incorrecto no coincide")
        void rechaza_el_secreto_incorrecto() {
            StepVerifier.create(hasher.matches("otro-secreto", HASH))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("nulos se tratan como no coincidencia, sin reventar")
        void tolera_nulos() {
            StepVerifier.create(hasher.matches(null, HASH)).expectNext(false).verifyComplete();
            StepVerifier.create(hasher.matches("x", null)).expectNext(false).verifyComplete();
        }

        @Test
        @DisplayName("la verificacion en vacio devuelve falso pero si ejecuta el calculo")
        void la_verificacion_en_vacio_cuesta_lo_mismo() {
            long inicio = System.nanoTime();
            StepVerifier.create(hasher.matchesNothing()).expectNext(false).verifyComplete();
            long transcurrido = (System.nanoTime() - inicio) / 1_000_000;

            // Si no calculara nada, terminaria en microsegundos y el tiempo de respuesta
            // delataria que el cliente no existe.
            assertThat(transcurrido).isGreaterThan(1);
        }
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
                            new SecurityProperties.Jwt(SECRET, null, null),
                            new SecurityProperties.RateLimit(false, 120, 1)),
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
