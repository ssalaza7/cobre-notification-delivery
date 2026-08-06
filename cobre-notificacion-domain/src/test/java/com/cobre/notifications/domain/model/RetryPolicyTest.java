package com.cobre.notifications.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    private static final List<Duration> DELAYS = List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2));

    /** Jitter maximo determinista: nextDouble() cercano a 1. */
    private static final RandomGenerator MAX_JITTER = () -> Long.MAX_VALUE;

    /** Sin jitter: nextDouble() igual a 0. */
    private static final RandomGenerator NO_JITTER = () -> 0L;

    @Test
    @DisplayName("cada intento fallido escala al siguiente escalon de espera")
    void escala_el_backoff() {
        RetryPolicy policy = new RetryPolicy(5, DELAYS, 0);

        assertThat(policy.nextDelay(1, NO_JITTER)).contains(Duration.ofSeconds(5));
        assertThat(policy.nextDelay(2, NO_JITTER)).contains(Duration.ofSeconds(30));
        assertThat(policy.nextDelay(3, NO_JITTER)).contains(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("con mas intentos que escalones, el backoff se estanca en el ultimo")
    void repite_el_ultimo_escalon() {
        RetryPolicy policy = new RetryPolicy(6, DELAYS, 0);

        assertThat(policy.nextDelay(4, NO_JITTER)).contains(Duration.ofMinutes(2));
        assertThat(policy.nextDelay(5, NO_JITTER)).contains(Duration.ofMinutes(2));
        assertThat(policy.baseDelay(10)).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("agotado el maximo de intentos ya no hay reintento")
    void se_agota() {
        RetryPolicy policy = new RetryPolicy(3, DELAYS, 0);

        assertThat(policy.isExhausted(2)).isFalse();
        assertThat(policy.isExhausted(3)).isTrue();
        assertThat(policy.nextDelay(3, NO_JITTER)).isEmpty();
    }

    @Test
    @DisplayName("sin intentos previos no corresponde calcular espera")
    void sin_intentos_no_hay_espera() {
        RetryPolicy policy = new RetryPolicy(3, DELAYS, 0.2);

        assertThat(policy.nextDelay(0, NO_JITTER)).isEmpty();
    }

    @Test
    @DisplayName("el jitter solo agrega tiempo y nunca alcanza el escalon siguiente")
    void el_jitter_queda_acotado() {
        RetryPolicy policy = new RetryPolicy(5, DELAYS, 0.2);

        Optional<Duration> jittered = policy.nextDelay(1, MAX_JITTER);

        assertThat(jittered).isPresent();
        assertThat(jittered.get()).isGreaterThanOrEqualTo(Duration.ofSeconds(5));
        assertThat(jittered.get()).isLessThan(Duration.ofSeconds(6));
        // El jitter nunca alcanza el escalon siguiente: las esperas se dispersan sin
        // desordenar la progresion del backoff.
        assertThat(jittered.get()).isLessThan(DELAYS.get(1));
    }

    @Test
    @DisplayName("con jitter en cero la espera es exactamente el escalon")
    void sin_jitter_la_espera_es_exacta() {
        RetryPolicy policy = new RetryPolicy(5, DELAYS, 0);

        assertThat(policy.nextDelay(1, MAX_JITTER)).contains(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("una politica sin escalones o con parametros absurdos no se puede construir")
    void valida_su_configuracion() {
        assertThatThrownBy(() -> new RetryPolicy(0, DELAYS, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");

        assertThatThrownBy(() -> new RetryPolicy(3, List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escalon");

        assertThatThrownBy(() -> new RetryPolicy(3, DELAYS, 1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jitterRatio");
    }
}
