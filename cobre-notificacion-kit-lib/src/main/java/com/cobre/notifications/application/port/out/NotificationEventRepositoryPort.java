package com.cobre.notifications.application.port.out;

import com.cobre.notifications.domain.model.EventQuery;
import com.cobre.notifications.domain.model.NotificationEvent;
import com.cobre.notifications.domain.model.PageResult;
import com.cobre.notifications.domain.model.StatusCount;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Puerto de salida hacia el almacen de notificaciones.
 *
 * <p>Nota sobre la pureza hexagonal: la firma usa {@code Mono}/{@code Flux}. Reactor es
 * una libreria de composicion asincrona, no un framework de infraestructura; el dominio
 * y los casos de uso siguen sin conocer Spring, HTTP, SQL ni ningun broker. La
 * alternativa (devolver {@code CompletionStage}) obligaria a traducir en cada adaptador
 * sin ganar independencia real.
 */
public interface NotificationEventRepositoryPort {

    /**
     * Inserta el evento solo si su identificador no existe todavia.
     *
     * @return {@code true} si se inserto, {@code false} si ya existia
     */
    Mono<Boolean> insertIfAbsent(NotificationEvent event);

    /**
     * Actualiza el estado de entrega con bloqueo optimista.
     *
     * <p>Recibe los dos extremos de la transicion y no solo el resultado: del anterior
     * sale la version con la que se leyo —que es la condicion de la escritura— y el
     * estado del que se viene, que es lo que permite mantener al dia el conteo por
     * estado sin recorrer la tabla.
     *
     * @param previous evento tal como se leyo
     * @param updated  evento ya transicionado
     * @return el evento actualizado, o vacio si otra ejecucion lo modifico primero
     */
    Mono<NotificationEvent> update(NotificationEvent previous, NotificationEvent updated);

    Mono<NotificationEvent> findById(String eventId);

    /** Busqueda acotada al tenant: es la unica via de acceso desde la API self-service. */
    Mono<NotificationEvent> findByIdAndClientId(String eventId, String clientId);

    /** Pagina de resultados con el cursor para continuar. Ver {@link EventQuery}. */
    Mono<PageResult<NotificationEvent>> search(EventQuery query);

    /** Conteo global por estado para publicar gauges de backlog. */
    Flux<StatusCount> countByStatus();
}
