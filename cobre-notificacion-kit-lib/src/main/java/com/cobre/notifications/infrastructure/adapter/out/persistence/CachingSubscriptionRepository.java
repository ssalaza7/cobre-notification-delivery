package com.cobre.notifications.infrastructure.adapter.out.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.cobre.notifications.application.port.out.SubscriptionRepositoryPort;
import com.cobre.notifications.domain.model.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Conserva en memoria la suscripcion ya resuelta durante un plazo corto.
 *
 * <p>Existe por una razon concreta: el worker resuelve el destino en <b>cada intento</b>,
 * reintentos incluidos, y desde que el secreto vive en un almacen aparte cada resolucion
 * es una llamada facturada. Un cliente con el webhook caido generaba una por intento.
 *
 * <p>Es un decorador y no un cambio en el adaptador porque cachear no es asunto de quien
 * habla con DynamoDB: quien lo envuelve decide si quiere caché y con que vigencia.
 *
 * <p><b>El precio:</b> un cambio de URL o una rotacion de secreto tardan hasta un plazo
 * en aplicarse. Con vigencias de segundos es asumible; con vigencias largas, no, porque
 * un cliente que corrige su webhook seguiria sin recibir nada.
 *
 * <p>Solo se cachea la lectura del destino, que es la del camino caliente. El listado y
 * el alta van directos: se piden pocas veces y el alta ademas debe verse enseguida.
 */
public class CachingSubscriptionRepository implements SubscriptionRepositoryPort {

    private record Entrada(Subscription subscription, Instant expira) {
        boolean vigente(Instant ahora) {
            return ahora.isBefore(expira);
        }
    }

    private final SubscriptionRepositoryPort delegado;
    private final Duration vigencia;
    private final Clock clock;
    private final Map<String, Entrada> cache = new ConcurrentHashMap<>();

    public CachingSubscriptionRepository(
            SubscriptionRepositoryPort delegado, Duration vigencia, Clock clock) {
        this.delegado = delegado;
        this.vigencia = vigencia;
        this.clock = clock;
    }

    @Override
    public Mono<Subscription> findActiveFor(String clientId, String eventType) {
        String clave = clientId + "|" + eventType;
        Instant ahora = clock.instant();

        Entrada guardada = cache.get(clave);
        if (guardada != null && guardada.vigente(ahora)) {
            return Mono.just(guardada.subscription());
        }
        // Se limpia lo vencido al pasar por aqui, en vez de con un temporizador: el mapa
        // crece con el numero de clientes activos, no sin limite.
        cache.remove(clave);

        return delegado.findActiveFor(clientId, eventType)
                .doOnNext(s -> cache.put(clave, new Entrada(s, ahora.plus(vigencia))));
    }

    /** Sin cachear: se consulta pocas veces y debe reflejar un alta reciente. */
    @Override
    public Flux<Subscription> findAllActiveByClientId(String clientId) {
        return delegado.findAllActiveByClientId(clientId);
    }

    /**
     * Sin cachear, y ademas invalida: tras registrar un destino nuevo, la siguiente
     * entrega debe ir alli y no esperar a que venza nada.
     */
    @Override
    public Mono<Subscription> save(Subscription subscription) {
        return delegado.save(subscription)
                .doOnNext(guardada -> {
                    cache.remove(guardada.clientId() + "|" + guardada.eventType());
                    cache.remove(guardada.clientId() + "|" + Subscription.ALL_EVENT_TYPES);
                });
    }
}
