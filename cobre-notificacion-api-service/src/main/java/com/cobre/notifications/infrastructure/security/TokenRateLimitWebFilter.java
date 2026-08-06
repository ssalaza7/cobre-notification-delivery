package com.cobre.notifications.infrastructure.security;

import com.cobre.notifications.infrastructure.config.SecurityProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limita los intentos contra el endpoint de emision de tokens.
 *
 * <p>Es un limite aparte del general y mucho mas estricto, porque protege de algo
 * distinto. El limite general evita que un cliente legitimo sature la API; este evita
 * que alguien pruebe secretos a ciegas. Y no puede ir por cliente autenticado, porque
 * justamente en este endpoint todavia no hay nadie autenticado: la cuota va por
 * direccion de origen.
 *
 * <p>Va antes que la cadena de seguridad, no despues, para que un ataque se corte
 * cuanto antes: verificar un hash bcrypt cuesta CPU a proposito, y ese costo es
 * precisamente lo que un atacante querria hacernos pagar en masa.
 *
 * <p>Limitacion conocida: el contador vive en memoria y por tanto es por instancia.
 * Contiene el intento torpe; frente a un ataque distribuido el control correcto esta en
 * el borde, con una regla por tasa en el WAF.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TokenRateLimitWebFilter implements WebFilter {

    private static final String TOKEN_PATH = "/oauth/token";

    private final SecurityProperties.RateLimit config;
    private final Clock clock;
    private final Map<String, Window> attempts = new ConcurrentHashMap<>();

    public TokenRateLimitWebFilter(SecurityProperties properties, Clock clock) {
        this.config = properties.rateLimit();
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!config.enabled() || !TOKEN_PATH.equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        if (allow(originOf(exchange))) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("Retry-After", "60");
        return exchange.getResponse().setComplete();
    }

    /**
     * Direccion de origen de la peticion.
     *
     * <p>Se usa la conexion directa y <b>no</b> {@code X-Forwarded-For}, que es una
     * cabecera que el propio cliente puede falsificar: confiar en ella sin un proxy que
     * la reescriba permitiria saltarse el limite cambiando un valor. Detras de un
     * balanceador hay que configurar explicitamente cuantos saltos son de confianza.
     */
    private String originOf(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "desconocido";
    }

    private boolean allow(String origin) {
        long currentMinute = clock.millis() / 60_000;
        Window window = attempts.compute(origin, (ignored, existing) ->
                existing == null || existing.minute != currentMinute
                        ? new Window(currentMinute)
                        : existing);
        return window.hits.incrementAndGet() <= config.tokenRequestsPerMinute();
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "PT5M")
    void evictStaleWindows() {
        long currentMinute = clock.millis() / 60_000;
        attempts.values().removeIf(window -> window.minute < currentMinute - 1);
    }

    private static final class Window {
        private final long minute;
        private final AtomicInteger hits = new AtomicInteger();

        private Window(long minute) {
            this.minute = minute;
        }
    }
}
