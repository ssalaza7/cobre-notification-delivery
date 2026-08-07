package com.cobre.notifications.domain.exception;

/**
 * El evento no existe, o existe pero pertenece a otro cliente.
 *
 * <p>Deliberadamente es la misma excepcion en ambos casos: responder 403 cuando el
 * recurso existe pero es de otro tenant convierte la API en un oraculo que permite
 * enumerar identificadores ajenos. Hacia afuera, ambos casos son 404.
 */
public class NotificationEventNotFoundException extends RuntimeException {

    private final String eventId;

    public NotificationEventNotFoundException(String eventId) {
        super("No existe la notificacion " + eventId);
        this.eventId = eventId;
    }

    public String eventId() {
        return eventId;
    }
}
