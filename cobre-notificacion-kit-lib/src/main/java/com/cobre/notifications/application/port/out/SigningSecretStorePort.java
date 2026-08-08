package com.cobre.notifications.application.port.out;

import reactor.core.publisher.Mono;

/**
 * Puerto de salida hacia el almacen de secretos de firma.
 *
 * <p>El secreto con el que se firma el payload saliente no vive junto a la suscripcion:
 * quien pueda leer esa tabla sabria a donde se entrega, pero no podria falsificar una
 * notificacion. La tabla guarda solo una referencia; el valor esta aqui, tras un permiso
 * distinto.
 *
 * <p>Es el mismo criterio que ya se aplicaba a las credenciales de cliente, que este
 * servicio dejo de custodiar.
 */
public interface SigningSecretStorePort {

    /**
     * Guarda el secreto y devuelve la referencia con la que recuperarlo.
     *
     * <p>Si ya existe una para ese cliente y tipo de evento, se devuelve sin tocar el
     * valor: rotar el secreto al cambiar la URL rompería la verificacion de firma del
     * cliente sin avisarle.
     */
    Mono<String> store(String clientId, String eventType, String secret);

    /** Valor del secreto, o vacio si la referencia ya no existe. */
    Mono<String> read(String reference);
}
