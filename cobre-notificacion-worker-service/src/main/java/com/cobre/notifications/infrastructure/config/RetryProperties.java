package com.cobre.notifications.infrastructure.config;

import com.cobre.notifications.domain.model.RetryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Escalones de reintento configurables.
 *
 * <p>Cada escalon se traduce en una cola de retardo del broker, asi que cambiar esta
 * lista cambia la topologia declarada al arrancar.
 */
@ConfigurationProperties(prefix = "cobre.retry")
public record RetryProperties(int maxAttempts, List<Duration> delays, double jitterRatio) {

    public RetryPolicy toPolicy() {
        return new RetryPolicy(maxAttempts, delays, jitterRatio);
    }
}
