package com.cobre.notifications.domain.exception;

/**
 * Las credenciales presentadas no son validas.
 *
 * <p>Es deliberadamente una sola excepcion para todos los casos: cliente inexistente,
 * secreto incorrecto o credencial desactivada. Distinguirlos hacia afuera convertiria
 * el endpoint en un oraculo para averiguar que identificadores existen, que es el
 * primer paso de un ataque de fuerza bruta dirigido.
 */
public class InvalidClientCredentialsException extends RuntimeException {

    public InvalidClientCredentialsException() {
        super("Las credenciales del cliente no son validas");
    }
}
