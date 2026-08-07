package com.cobre.notifications.infrastructure.observability;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Hace que el MDC sobreviva a los saltos de hilo de la cadena reactiva.
 *
 * <p>Este es el punto que rompe el logging estructurado en WebFlux y que no aparece
 * en un servicio bloqueante. El MDC de SLF4J es un {@code ThreadLocal}: en Spring MVC
 * un hilo atiende una peticion de principio a fin y el contexto sigue ahi. En WebFlux
 * la misma entrega salta del event loop de Netty al hilo del driver de Postgres y de
 * vuelta, asi que para cuando se escribe la linea de log el {@code ThreadLocal} esta
 * vacio y todos los campos de correlacion salen nulos.
 *
 * <p>La solucion tiene dos mitades. {@code Hooks.enableAutomaticContextPropagation()}
 * hace que Reactor restaure los {@code ThreadLocal} registrados en cada transicion de
 * hilo, y cada {@code ThreadLocalAccessor} le ensena a Reactor como leer y escribir
 * una clave concreta del MDC. A partir de ahi, un {@code contextWrite} en el
 * adaptador basta: los casos de uso siguen usando SLF4J normal y sin saber nada de
 * esto.
 *
 * <p>El costo es real y conviene decirlo: restaurar el contexto en cada salto de hilo
 * no es gratis. Por eso {@link LogFields} tiene tres claves y no quince.
 */
@Configuration
public class ReactiveMdcConfig {

    @PostConstruct
    void enableMdcPropagation() {
        Hooks.enableAutomaticContextPropagation();

        ContextRegistry registry = ContextRegistry.getInstance();
        for (String field : LogFields.ALL) {
            registry.registerThreadLocalAccessor(
                    field,
                    () -> MDC.get(field),
                    value -> MDC.put(field, value),
                    () -> MDC.remove(field));
        }
    }
}
