package com.cobre.notifications.infrastructure.adapter.out.webhook;

import com.cobre.notifications.application.port.out.WebhookClientPort;
import com.cobre.notifications.domain.exception.InvalidWebhookUrlException;
import com.cobre.notifications.domain.model.WebhookDeliveryRequest;
import com.cobre.notifications.domain.model.WebhookDeliveryResult;
import com.cobre.notifications.infrastructure.config.WebhookProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Adaptador de salida que entrega la notificacion al webhook del cliente.
 *
 * <p>Traduce el mundo HTTP al vocabulario del dominio. La regla clave es la
 * clasificacion del resultado: reintentar lo que puede mejorar (5xx, 408, 429,
 * timeouts, errores de conexion) y no reintentar lo que no (4xx de contrato,
 * redirecciones, URL invalida).
 *
 * <p>Nunca propaga una excepcion: el caso de uso siempre recibe un resultado que
 * registrar en la bitacora, incluso cuando el destino no respondio nada.
 */
@Component
public class WebClientWebhookAdapter implements WebhookClientPort {

    private static final Logger log = LoggerFactory.getLogger(WebClientWebhookAdapter.class);

    /** Codigos que si mejoran con un reintento: sobrecarga, mantenimiento o limite temporal. */
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(408, 425, 429);

    private final WebClient webClient;
    private final WebhookUrlValidator urlValidator;
    private final WebhookSigner signer;
    private final ObjectMapper objectMapper;
    private final WebhookProperties properties;
    private final Clock clock;

    public WebClientWebhookAdapter(
            WebClient webhookWebClient,
            WebhookUrlValidator urlValidator,
            WebhookSigner signer,
            ObjectMapper objectMapper,
            WebhookProperties properties,
            Clock clock) {
        this.webClient = webhookWebClient;
        this.urlValidator = urlValidator;
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Mono<WebhookDeliveryResult> deliver(WebhookDeliveryRequest request) {
        long startedAt = System.nanoTime();
        LongSupplier elapsed = () -> (System.nanoTime() - startedAt) / 1_000_000;

        // La validacion resuelve DNS, que es bloqueante: fuera del event loop de Netty.
        return Mono.fromCallable(() -> urlValidator.validate(request.targetUrl()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(uri -> send(uri, request, elapsed))
                .onErrorResume(InvalidWebhookUrlException.class, e -> {
                    log.error("Destino invalido para la notificacion {}: {}",
                            request.event().eventId(), e.getMessage());
                    // Una URL invalida no se arregla reintentando; es configuracion del cliente.
                    return Mono.just(WebhookDeliveryResult.permanent(null, e.getMessage(), elapsed.getAsLong()));
                })
                .onErrorResume(e -> {
                    log.warn("Fallo de transporte entregando {}: {}",
                            request.event().eventId(), e.toString());
                    return Mono.just(WebhookDeliveryResult.retryable(null, describe(e), elapsed.getAsLong()));
                });
    }

    private Mono<WebhookDeliveryResult> send(URI uri, WebhookDeliveryRequest request, LongSupplier elapsed) {
        String payload = serialize(request);
        Instant signedAt = clock.instant();

        // event-timestamp y event-signature son las cabeceras que Cobre ya documenta
        // para sus webhooks. Las X-Cobre-* son adicionales y sirven para que el
        // receptor pueda deduplicar y distinguir un reintento sin abrir el cuerpo.
        return webClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .header(WebhookSigner.TIMESTAMP_HEADER, String.valueOf(signedAt.getEpochSecond()))
                .header(WebhookSigner.SIGNATURE_HEADER, signer.sign(payload, request.signingSecret(), signedAt))
                .header("X-Cobre-Event-Id", request.event().eventId())
                .header("X-Cobre-Event-Type", request.event().eventType())
                .header("X-Cobre-Delivery-Attempt", String.valueOf(request.attemptNumber()))
                .bodyValue(payload)
                .exchangeToMono(response -> classify(response, elapsed));
    }

    private Mono<WebhookDeliveryResult> classify(ClientResponse response, LongSupplier elapsed) {
        HttpStatusCode status = response.statusCode();

        if (status.is2xxSuccessful()) {
            // No interesa el cuerpo de una respuesta exitosa, pero hay que liberarlo
            // para no filtrar la conexion del pool.
            return response.releaseBody()
                    .thenReturn(WebhookDeliveryResult.delivered(status.value(), elapsed.getAsLong()));
        }

        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> {
                    String message = "El webhook respondio " + status.value() + snippet(body);
                    return isRetryable(status)
                            ? WebhookDeliveryResult.retryable(status.value(), message, elapsed.getAsLong())
                            : WebhookDeliveryResult.permanent(status.value(), message, elapsed.getAsLong());
                });
    }

    /**
     * Las redirecciones cuentan como fallo permanente y no se siguen: seguir un 302
     * dejaria que el destino redirija la peticion hacia la red interna, evadiendo la
     * validacion anti-SSRF que se hizo sobre la URL original.
     */
    private boolean isRetryable(HttpStatusCode status) {
        return status.is5xxServerError() || RETRYABLE_STATUSES.contains(status.value());
    }

    private String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.strip();
        int max = properties.maxResponseBytes();
        return ": " + (trimmed.length() <= max ? trimmed : trimmed.substring(0, max));
    }

    private String describe(Throwable error) {
        String cause = error.getCause() != null ? error.getCause().getMessage() : null;
        return cause != null ? error.getClass().getSimpleName() + ": " + cause : error.toString();
    }

    private String serialize(WebhookDeliveryRequest request) {
        return objectMapper.writeValueAsString(WebhookPayload.from(request));
    }
}
