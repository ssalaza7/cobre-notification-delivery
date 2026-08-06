package com.cobre.notifications.infrastructure.security;

import com.cobre.notifications.infrastructure.config.SecurityProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limita las peticiones por cliente autenticado.
 *
 * <p>Sin limite, un solo cliente con un bucle mal escrito degrada la API para todos:
 * es denegacion de servicio sin necesidad de mala intencion (OWASP A04, "Unrestricted
 * Resource Consumption" en el Top 10 de APIs).
 *
 * <p>Limitacion conocida y deliberada: el contador vive en memoria, asi que el limite
 * real es por instancia. Con tres replicas, el limite efectivo es el triple. Se acepta
 * porque el objetivo aqui es contener el abuso accidental; el limite exacto y
 * compartido pertenece al API gateway o a un contador en Redis, que es donde se
 * resuelve en produccion sin volver a este codigo.
 */
@Component
public class RateLimitWebFilter implements WebFilter {

    private final SecurityProperties.RateLimit config;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitWebFilter(SecurityProperties properties, Clock clock) {
        this.config = properties.rateLimit();
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!config.enabled()) {
            return chain.filter(exchange);
        }
        // Primero se decide y despues se actua. Encadenar el chain dentro del flatMap y
        // rematar con switchIfEmpty no funcionaria: un Mono<Void> siempre completa
        // vacio, asi que switchIfEmpty se disparara y la peticion pasaria dos veces.
        Mono<Boolean> allowed = ReactiveSecurityContextHolder.getContext()
                .flatMap(context -> Mono.justOrEmpty(context.getAuthentication()))
                .map(authentication -> allow(clientKey(authentication)))
                // Sin contexto de seguridad (por ejemplo, /actuator/health) no hay a
                // quien limitar; el control de abuso anonimo es del borde.
                .defaultIfEmpty(true);

        return allowed.flatMap(pass -> pass ? chain.filter(exchange) : reject(exchange));
    }

    private String clientKey(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String clientId = jwt.getClaimAsString("client_id");
            if (clientId != null && !clientId.isBlank()) {
                return clientId;
            }
        }
        return authentication != null ? authentication.getName() : "anonymous";
    }

    /** Ventana fija de un minuto. */
    private boolean allow(String key) {
        long currentMinute = clock.millis() / 60_000;
        Window window = windows.compute(key, (ignored, existing) ->
                existing == null || existing.minute != currentMinute
                        ? new Window(currentMinute)
                        : existing);
        return window.hits.incrementAndGet() <= config.requestsPerMinute();
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("Retry-After", "60");
        return exchange.getResponse().setComplete();
    }

    /**
     * Purga las ventanas que ya no cuentan. Sin esto, el mapa crece con cada cliente
     * que aparece una sola vez y no se libera nunca.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "PT5M")
    void evictStaleWindows() {
        long currentMinute = clock.millis() / 60_000;
        windows.values().removeIf(window -> window.minute < currentMinute - 1);
    }

    private static final class Window {
        private final long minute;
        private final AtomicInteger hits = new AtomicInteger();

        private Window(long minute) {
            this.minute = minute;
        }
    }
}
