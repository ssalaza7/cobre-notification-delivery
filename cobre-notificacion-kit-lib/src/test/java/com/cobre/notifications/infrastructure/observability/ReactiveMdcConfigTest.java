package com.cobre.notifications.infrastructure.observability;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica lo que hace util al logging estructurado en WebFlux: que los campos de
 * correlacion sobrevivan a los saltos de hilo.
 *
 * <p>Sin esta pieza, una entrega que empieza en el hilo del broker y termina en el de
 * Netty escribe sus ultimas lineas con el MDC vacio, y en Kibana quedan sueltas.
 */
class ReactiveMdcConfigTest {

    private final ReactiveMdcConfig config = new ReactiveMdcConfig();

    @BeforeEach
    void setUp() {
        config.enableMdcPropagation();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private Optional<ThreadLocalAccessor<?>> accessorFor(String key) {
        for (ThreadLocalAccessor<?> accessor : ContextRegistry.getInstance().getThreadLocalAccessors()) {
            if (key.equals(accessor.key())) {
                return Optional.of(accessor);
            }
        }
        return Optional.empty();
    }

    @Test
    @DisplayName("registra un accesor por cada campo de correlacion")
    void registra_un_accesor_por_campo() {
        for (String field : LogFields.ALL) {
            assertThat(accessorFor(field))
                    .as("accesor para %s", field)
                    .isPresent();
        }
    }

    @Test
    @DisplayName("el accesor lee, escribe y limpia el MDC")
    @SuppressWarnings("unchecked")
    void el_accesor_manipula_el_mdc() {
        ThreadLocalAccessor<String> accessor =
                (ThreadLocalAccessor<String>) accessorFor(LogFields.EVENT_ID).orElseThrow();

        accessor.setValue("EVT001");
        assertThat(MDC.get(LogFields.EVENT_ID)).isEqualTo("EVT001");
        assertThat(accessor.getValue()).isEqualTo("EVT001");

        accessor.setValue();
        assertThat(MDC.get(LogFields.EVENT_ID)).isNull();
    }

    @Test
    @DisplayName("registrar dos veces no duplica accesores: el arranque puede repetirse")
    void es_idempotente() {
        config.enableMdcPropagation();

        List<String> keys = ContextRegistry.getInstance().getThreadLocalAccessors().stream()
                .map(accessor -> String.valueOf(accessor.key()))
                .filter(LogFields.ALL::contains)
                .toList();

        assertThat(keys).hasSameSizeAs(LogFields.ALL);
    }

    @Test
    @DisplayName("el campo puesto en el contexto reactivo llega al MDC en otro hilo")
    void el_contexto_sobrevive_al_salto_de_hilo() {
        AtomicReference<String> visto = new AtomicReference<>();
        AtomicReference<String> hilo = new AtomicReference<>();

        Mono<Void> cadena = Mono.fromRunnable(() -> {
                    visto.set(MDC.get(LogFields.EVENT_ID));
                    hilo.set(Thread.currentThread().getName());
                })
                // Fuerza el cambio de hilo que en produccion provoca el driver de la base
                // o la respuesta del webhook.
                .subscribeOn(Schedulers.boundedElastic())
                .then()
                .contextWrite(Context.of(LogFields.EVENT_ID, "EVT-PROPAGADO"));

        StepVerifier.create(cadena).verifyComplete();

        assertThat(visto.get()).isEqualTo("EVT-PROPAGADO");
        assertThat(hilo.get()).isNotEqualTo(Thread.currentThread().getName());
    }
}
