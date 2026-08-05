package com.cobre.notifications.infrastructure.observability;

import java.util.List;

/**
 * Campos de correlacion que viajan en el MDC y terminan como campos indexados en
 * Elasticsearch.
 *
 * <p>Son pocos y deliberados. Cada campo aqui es un campo mas en cada documento de
 * cada log, y en Elasticsearch eso se paga en almacenamiento y en velocidad de
 * consulta. Estos tres son los que responden las preguntas que de verdad se hacen en
 * una incidencia: "que paso con esta notificacion", "que le paso a este cliente" y
 * "que hizo esta peticion".
 *
 * <p>Lo que <b>no</b> esta aqui es tan importante como lo que si: el {@code content}
 * de la notificacion nunca se registra. Es informacion financiera del cliente
 * ("Credit card payment received for $150.00") y mandarla a un indice de logs la
 * replica en un sistema con otro modelo de acceso, otra retencion y otro respaldo.
 */
public final class LogFields {

    /** Identificador de la peticion HTTP; permite seguir una llamada de punta a punta. */
    public static final String REQUEST_ID = "request_id";

    /** Tenant dueno de los datos. Es el corte natural ante una queja de un cliente. */
    public static final String CLIENT_ID = "client_id";

    /** Notificacion concreta; agrupa todos los intentos de entrega de un mismo evento. */
    public static final String EVENT_ID = "event_id";

    public static final List<String> ALL = List.of(REQUEST_ID, CLIENT_ID, EVENT_ID);

    private LogFields() {
    }
}
