package com.cobre.notifications.infrastructure.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Registra cada peticion a la API y propaga los campos de correlacion.
 *
 * <p>Escribe <b>una sola linea por peticion</b>, cuando ya se conoce la respuesta: el
 * metodo, la ruta, el codigo devuelto y cuanto tardo. Una linea al entrar y otra al
 * salir duplicaria el volumen sin agregar informacion, porque lo que interesa es el par
 * completo.
 *
 * <p>Respeta un {@code X-Request-Id} entrante si viene del borde y lo genera si no, de
 * modo que una misma traza atraviese varios servicios con el mismo identificador. Lo
 * devuelve tambien en la respuesta: cuando un cliente reporta un fallo, ese
 * identificador es lo unico que hace falta para encontrar su peticion.
 */
@Component
public class LoggingContextWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger("com.cobre.notifications.api");

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /**
     * Actuator no se registra.
     *
     * <p>Las sondas del orquestador consultan {@code health} cada pocos segundos y
     * Prometheus consulta las metricas cada cinco: registrarlo son decenas de miles de
     * lineas al dia que no ayudan a diagnosticar nada y que hay que pagar en indice.
     * Si esos endpoints fallan, se nota por la sonda y por la ausencia de metricas, no
     * por su log de acceso.
     */
    private static final String ACTUATOR_PATH = "/actuator";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = resolveRequestId(exchange);
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
        long startedAt = System.nanoTime();

        return ReactiveSecurityContextHolder.getContext()
                .flatMap(security -> Mono.justOrEmpty(security.getAuthentication()))
                .map(LoggingContextWebFilter::clientIdOf)
                .defaultIfEmpty("")
                .flatMap(clientId -> chain.filter(exchange)
                        .doFinally(signal -> logRequest(exchange, requestId, clientId, startedAt))
                        .contextWrite(context -> withLogFields(context, requestId, clientId)));
    }

    private void logRequest(ServerWebExchange exchange, String requestId, String clientId, long startedAt) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith(ACTUATOR_PATH)) {
            return;
        }

        HttpStatusCode status = exchange.getResponse().getStatusCode();
        int code = status != null ? status.value() : 0;
        long millis = (System.nanoTime() - startedAt) / 1_000_000;

        // Se escriben como MDC para que queden como campos indexados y no haya que
        // extraerlos del texto del mensaje con una expresion regular.
        MDC.put(LogFields.LOG_TYPE, LogFields.TYPE_API);
        MDC.put(LogFields.REQUEST_ID, requestId);
        MDC.put("http.method", exchange.getRequest().getMethod().name());
        MDC.put("http.path", path);
        MDC.put("http.status", String.valueOf(code));
        MDC.put("duration_ms", String.valueOf(millis));
        if (!clientId.isBlank()) {
            MDC.put(LogFields.CLIENT_ID, clientId);
        }
        try {
            // Un 5xx es problema nuestro y merece nivel de error; un 4xx es del cliente.
            if (code >= 500) {
                log.error("{} {} -> {} en {}ms", exchange.getRequest().getMethod(), path, code, millis);
            } else {
                log.info("{} {} -> {} en {}ms", exchange.getRequest().getMethod(), path, code, millis);
            }
        } finally {
            MDC.remove(LogFields.LOG_TYPE);
            MDC.remove(LogFields.REQUEST_ID);
            MDC.remove(LogFields.CLIENT_ID);
            MDC.remove("http.method");
            MDC.remove("http.path");
            MDC.remove("http.status");
            MDC.remove("duration_ms");
        }
    }

    private String resolveRequestId(ServerWebExchange exchange) {
        String incoming = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        if (incoming == null || incoming.isBlank()) {
            return UUID.randomUUID().toString();
        }
        // El valor viene de fuera y termina en un campo indexado: se acota para que nadie
        // pueda inflar el indice con una cabecera de un megabyte.
        return incoming.length() <= 64 ? incoming : incoming.substring(0, 64);
    }

    private static String clientIdOf(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            String clientId = jwt.getClaimAsString(LogFields.CLIENT_ID);
            return clientId != null ? clientId : "";
        }
        return "";
    }

    private Context withLogFields(Context context, String requestId, String clientId) {
        Map<String, String> fields = new HashMap<>();
        fields.put(LogFields.REQUEST_ID, requestId);
        if (!clientId.isBlank()) {
            fields.put(LogFields.CLIENT_ID, clientId);
        }
        return context.putAllMap(fields);
    }
}
