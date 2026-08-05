package com.cobre.notifications.application.port.out;

import reactor.core.publisher.Mono;

/**
 * Puerto de salida para verificar un secreto contra su hash.
 *
 * <p>Devuelve {@code Mono} y no un booleano directo porque un algoritmo de hash
 * deliberadamente lento —que es lo que se quiere aqui— bloquea el hilo que lo ejecuta.
 * El adaptador decide donde correrlo; el caso de uso no tiene por que enterarse.
 */
public interface SecretHasherPort {

    Mono<Boolean> matches(String rawSecret, String hash);

    /**
     * Consume el mismo tiempo que una verificacion real, sin comparar nada.
     *
     * <p>Sirve para que un cliente inexistente tarde lo mismo que uno con el secreto
     * equivocado. Sin esto, la diferencia de tiempo entre ambos casos revela que
     * identificadores existen, y eso convierte la fuerza bruta en algo dirigido.
     */
    Mono<Boolean> matchesNothing();
}
