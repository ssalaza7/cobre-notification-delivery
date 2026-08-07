package com.cobre.notifications.domain.model;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Politica de reintentos: backoff exponencial con jitter, expresado como una lista
 * de escalones de espera.
 *
 * <p>Los escalones son explicitos (5s, 30s, 2m, 10m, 15m) en vez de calculados con una
 * formula: asi la politica se lee de un vistazo y se ajusta sin tocar codigo. Ninguno
 * puede superar los 15 minutos, que es el tope de retardo por mensaje que impone SQS.
 *
 * <p>El jitter evita el efecto manada: si el webhook de un cliente se cae un minuto,
 * todas sus notificaciones fallan a la vez y sin jitter reintentarian todas en el
 * mismo instante, tumbandolo de nuevo justo cuando se estaba recuperando.
 */
public record RetryPolicy(int maxAttempts, List<Duration> delays, double jitterRatio) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts debe ser >= 1");
        }
        if (delays == null || delays.isEmpty()) {
            throw new IllegalArgumentException("se requiere al menos un escalon de espera");
        }
        if (jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("jitterRatio debe estar entre 0 y 1");
        }
        delays = List.copyOf(delays);
    }

    /** Se agotaron los reintentos tras {@code attemptsMade} intentos fallidos. */
    public boolean isExhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }

    /**
     * Espera antes del proximo intento, o vacio si ya no hay reintento posible.
     *
     * @param attemptsMade intentos fallidos acumulados en el ciclo actual
     */
    public Optional<Duration> nextDelay(int attemptsMade, RandomGenerator random) {
        if (attemptsMade < 1 || isExhausted(attemptsMade)) {
            return Optional.empty();
        }
        return Optional.of(applyJitter(baseDelay(attemptsMade), random));
    }

    /**
     * Escalon base sin jitter. Si hay mas reintentos que escalones definidos, se
     * repite el ultimo: el backoff deja de crecer en vez de dispararse.
     */
    public Duration baseDelay(int attemptsMade) {
        int index = Math.min(attemptsMade - 1, delays.size() - 1);
        return delays.get(index);
    }

    private Duration applyJitter(Duration base, RandomGenerator random) {
        if (jitterRatio == 0) {
            return base;
        }
        long extraMillis = (long) (base.toMillis() * jitterRatio * random.nextDouble());
        return base.plusMillis(extraMillis);
    }
}
