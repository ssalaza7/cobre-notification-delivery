package com.cobre.notifications.domain.model;

/**
 * Version optimista de una notificacion.
 *
 * <p>El par (intentos, reenvios) identifica de forma univoca el punto del ciclo de
 * vida en el que estaba el evento cuando se leyo. Al actualizar se exige que siga
 * igual: si dos consumidores procesan el mismo evento a la vez —cosa normal cuando
 * el broker reentrega un mensaje no confirmado— solo uno escribe, y el otro descubre
 * que su version quedo obsoleta en vez de sobrescribir un estado mas nuevo.
 */
public record EventVersion(int attempts, int replayCount) {
}
