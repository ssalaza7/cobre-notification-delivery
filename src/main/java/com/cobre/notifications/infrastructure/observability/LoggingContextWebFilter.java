package com.cobre.notifications.infrastructure.observability;

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
 * Pone el identificador de peticion y el cliente en el contexto de logging.
 *
 * <p>Respeta un {@code X-Request-Id} entrante si viene del borde (gateway o malla de
 * servicios) y lo genera si no. Asi una sola traza atraviesa varios servicios de la
 * plataforma con el mismo identificador, que es la unica forma de reconstruir un
 * incidente que cruzo tres microservicios.
 *
 * <p>Tambien lo devuelve en la respuesta: cuando un cliente reporta un fallo, ese
 * identificador es lo unico que hace falta para encontrar la peticion exacta en
 * Kibana.
 */
@Component
public class LoggingContextWebFilter implements WebFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = resolveRequestId(exchange);
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);

        return ReactiveSecurityContextHolder.getContext()
                .flatMap(security -> Mono.justOrEmpty(security.getAuthentication()))
                .map(LoggingContextWebFilter::clientIdOf)
                .defaultIfEmpty("")
                .flatMap(clientId -> chain.filter(exchange)
                        .contextWrite(context -> withLogFields(context, requestId, clientId)));
    }

    private String resolveRequestId(ServerWebExchange exchange) {
        String incoming = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        if (incoming == null || incoming.isBlank()) {
            return UUID.randomUUID().toString();
        }
        // El valor viene de fuera y termina en un campo indexado: se acota para que
        // nadie pueda inflar el indice con una cabecera de un megabyte.
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
