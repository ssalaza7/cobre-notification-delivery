package com.cobre.notifications.infrastructure.adapter.out.webhook;

import com.cobre.notifications.domain.model.AttemptOutcome;
import com.cobre.notifications.domain.model.NotificationEvent;
import com.cobre.notifications.domain.model.Subscription;
import com.cobre.notifications.domain.model.WebhookDeliveryRequest;
import com.cobre.notifications.domain.model.WebhookDeliveryResult;
import com.cobre.notifications.infrastructure.config.WebhookProperties;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.netty.channel.ChannelOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba del adaptador contra un servidor HTTP real del JDK.
 *
 * <p>Un servidor de verdad y no un mock del {@code WebClient}: lo que hay que
 * verificar aqui es justamente el comportamiento de red —codigos, timeouts,
 * conexiones rechazadas, redirecciones— que un mock daria por supuesto.
 */
class WebClientWebhookAdapterTest {

    private static final Instant NOW = Instant.parse("2024-03-15T12:00:00Z");

    private HttpServer server;
    private WebClientWebhookAdapter adapter;
    private final AtomicReference<Headers> receivedHeaders = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        adapter = adapterWith(Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private WebClientWebhookAdapter adapterWith(Duration responseTimeout) {
        WebhookProperties properties = new WebhookProperties(
                false, false, Duration.ofSeconds(2), responseTimeout, 64, null);

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                .responseTimeout(responseTimeout)
                .followRedirect(false);

        WebClient webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();

        ObjectMapper objectMapper = JsonMapper.builder().build();

        return new WebClientWebhookAdapter(
                webClient,
                new WebhookUrlValidator(properties),
                new WebhookSigner(),
                objectMapper,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void respondWith(int status, String body, long delayMillis) {
        server.createContext("/hook", exchange -> {
            receivedHeaders.set(exchange.getRequestHeaders());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
    }

    private WebhookDeliveryRequest requestTo(String path) {
        NotificationEvent event = NotificationEvent.received(
                "EVT001", "CLIENT001", "credit_transfer", "Transferencia de $1,500.00",
                NOW.minusSeconds(2), NOW.minusSeconds(2));
        Subscription subscription = new Subscription(
                UUID.randomUUID(), "CLIENT001", "*",
                "http://127.0.0.1:" + server.getAddress().getPort() + path,
                "whsec_test", true);
        return WebhookDeliveryRequest.of(event, subscription, 1);
    }

    @Test
    @DisplayName("una respuesta 2xx se traduce a entrega exitosa")
    void traduce_2xx_a_entrega() {
        respondWith(200, "ok", 0);

        StepVerifier.create(adapter.deliver(requestTo("/hook")))
                .assertNext(result -> {
                    assertThat(result.isDelivered()).isTrue();
                    assertThat(result.httpStatus()).isEqualTo(200);
                    assertThat(result.errorMessage()).isNull();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("el payload viaja firmado y con las cabeceras de correlacion")
    void firma_y_etiqueta_la_peticion() {
        respondWith(200, "ok", 0);

        StepVerifier.create(adapter.deliver(requestTo("/hook")))
                .expectNextMatches(WebhookDeliveryResult::isDelivered)
                .verifyComplete();

        Headers headers = receivedHeaders.get();
        assertThat(headers.getFirst("X-Cobre-Event-Id")).isEqualTo("EVT001");
        assertThat(headers.getFirst("X-Cobre-Event-Type")).isEqualTo("credit_transfer");
        assertThat(headers.getFirst("X-Cobre-Delivery-Attempt")).isEqualTo("1");
        assertThat(headers.getFirst(WebhookSigner.TIMESTAMP_HEADER))
                .isEqualTo(String.valueOf(NOW.getEpochSecond()));
        assertThat(headers.getFirst(WebhookSigner.SIGNATURE_HEADER))
                .isEqualTo(new WebhookSigner().sign(receivedBody.get(), "whsec_test", NOW));
        assertThat(receivedBody.get())
                .contains("\"event_id\":\"EVT001\"")
                .contains("\"client_id\":\"CLIENT001\"")
                .contains("\"attempt\":1");
    }

    @Test
    @DisplayName("un 5xx es transitorio: se reintenta")
    void trata_5xx_como_transitorio() {
        respondWith(503, "service unavailable", 0);

        StepVerifier.create(adapter.deliver(requestTo("/hook")))
                .assertNext(result -> {
                    assertThat(result.outcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
                    assertThat(result.httpStatus()).isEqualTo(503);
                    assertThat(result.errorMessage()).contains("503").contains("service unavailable");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("un 429 es transitorio: el cliente pide bajar el ritmo, no rechaza el evento")
    void trata_429_como_transitorio() {
        respondWith(429, "slow down", 0);

        StepVerifier.create(adapter.deliver(requestTo("/hook")))
                .assertNext(result -> assertThat(result.outcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE))
                .verifyComplete();
    }

    @Test
    @DisplayName("un 4xx de contrato es permanente: reintentarlo no cambia nada")
    void trata_4xx_como_permanente() {
        respondWith(400, "bad request", 0);

        StepVerifier.create(adapter.deliver(requestTo("/hook")))
                .assertNext(result -> {
                    assertThat(result.outcome()).isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
                    assertThat(result.httpStatus()).isEqualTo(400);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("no se siguen redirecciones: seguirlas evadiria la validacion anti-SSRF")
    void no_sigue_redirecciones() {
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        StepVerifier.create(adapter.deliver(requestTo("/redirect")))
                .assertNext(result -> {
                    assertThat(result.outcome()).isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
                    assertThat(result.httpStatus()).isEqualTo(302);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("el cuerpo de error se recorta para no guardar respuestas gigantes")
    void recorta_el_cuerpo_de_error() {
        respondWith(500, "e".repeat(5000), 0);

        StepVerifier.create(adapter.deliver(requestTo("/hook")))
                .assertNext(result -> assertThat(result.errorMessage()).hasSizeLessThan(200))
                .verifyComplete();
    }

    @Test
    @DisplayName("un destino que no responde a tiempo es un fallo transitorio")
    void trata_el_timeout_como_transitorio() {
        respondWith(200, "ok", 1500);

        StepVerifier.create(adapterWith(Duration.ofMillis(300)).deliver(requestTo("/hook")))
                .assertNext(result -> {
                    assertThat(result.outcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
                    assertThat(result.httpStatus()).isNull();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("una conexion rechazada es transitoria: el destino puede volver")
    void trata_la_conexion_rechazada_como_transitoria() {
        NotificationEvent event = NotificationEvent.received(
                "EVT001", "CLIENT001", "credit_transfer", "contenido", NOW, NOW);
        Subscription unreachable = new Subscription(
                UUID.randomUUID(), "CLIENT001", "*", "http://127.0.0.1:1/hook", "whsec_test", true);

        StepVerifier.create(adapter.deliver(WebhookDeliveryRequest.of(event, unreachable, 1)))
                .assertNext(result -> assertThat(result.outcome())
                        .isEqualTo(AttemptOutcome.RETRYABLE_FAILURE))
                .verifyComplete();
    }

    @Test
    @DisplayName("una URL invalida es un fallo permanente y ni siquiera se intenta la peticion")
    void trata_url_invalida_como_permanente() {
        NotificationEvent event = NotificationEvent.received(
                "EVT001", "CLIENT001", "credit_transfer", "contenido", NOW, NOW);
        Subscription invalid = new Subscription(
                UUID.randomUUID(), "CLIENT001", "*", "ftp://cliente.example.com/hook", "whsec_test", true);

        StepVerifier.create(adapter.deliver(WebhookDeliveryRequest.of(event, invalid, 1)))
                .assertNext(result -> {
                    assertThat(result.outcome()).isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
                    assertThat(result.httpStatus()).isNull();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("el adaptador nunca propaga una excepcion: siempre devuelve un resultado")
    void nunca_propaga_excepciones() {
        NotificationEvent event = NotificationEvent.received(
                "EVT001", "CLIENT001", "credit_transfer", "contenido", NOW, NOW);
        Subscription broken = new Subscription(
                UUID.randomUUID(), "CLIENT001", "*", "https:///sin-host", "whsec_test", true);

        Mono<WebhookDeliveryResult> delivery = adapter.deliver(WebhookDeliveryRequest.of(event, broken, 1));

        StepVerifier.create(delivery)
                .assertNext(result -> assertThat(result.outcome().isFailure()).isTrue())
                .verifyComplete();
    }
}
