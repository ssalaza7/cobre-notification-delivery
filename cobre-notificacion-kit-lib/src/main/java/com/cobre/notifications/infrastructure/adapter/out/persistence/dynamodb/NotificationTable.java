package com.cobre.notifications.infrastructure.adapter.out.persistence.dynamodb;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Esquema de la tabla unica de notificaciones.
 *
 * <p>Un evento y sus intentos comparten particion ({@code EVENT#{id}}) y se distinguen
 * por la clave de orden: {@code META} el estado del evento, {@code ATTEMPT#...} cada
 * intento. Asi el detalle de la API —evento mas bitacora— se resuelve con una sola
 * consulta en vez de las dos que exigia el modelo relacional.
 *
 * <p>Los intentos van como items propios y no como una lista dentro del evento por tres
 * razones: el TTL de DynamoDB expira items y no elementos de una lista; cada reenvio
 * manual abre un ciclo nuevo, asi que la lista no tiene cota superior frente al limite
 * de 400 KB por item; y cada intento obligaria a reescribir el evento entero, contenido
 * incluido, que es lo que se factura como capacidad de escritura.
 */
final class NotificationTable {

    static final String PK = "pk";
    static final String SK = "sk";

    /** Claves del indice por cliente y fecha. Solo las llevan los items {@code META}. */
    static final String GSI_PK = "gsi1pk";
    static final String GSI_SK = "gsi1sk";

    static final String META = "META";
    static final String ATTEMPT_PREFIX = "ATTEMPT#";
    static final String STATUS_PREFIX = "STATUS#";

    static final String TTL = "ttl";

    // Atributos del evento.
    static final String EVENT_ID = "event_id";
    static final String CLIENT_ID = "client_id";
    static final String EVENT_TYPE = "event_type";
    static final String CONTENT = "content";
    static final String CREATED_AT = "created_at";
    static final String DELIVERY_STATUS = "delivery_status";
    static final String DELIVERY_DATE = "delivery_date";
    static final String ATTEMPTS = "attempts";
    static final String REPLAY_COUNT = "replay_count";
    static final String WEBHOOK_URL = "webhook_url";
    static final String LAST_HTTP_STATUS = "last_http_status";
    static final String LAST_ERROR = "last_error";
    static final String UPDATED_AT = "updated_at";

    // Atributos del intento.
    static final String ATTEMPT_ID = "id";
    static final String ATTEMPT_NUMBER = "attempt_number";
    static final String ATTEMPTED_AT = "attempted_at";
    static final String OUTCOME = "outcome";
    static final String HTTP_STATUS = "http_status";
    static final String DURATION_MS = "duration_ms";
    static final String ERROR_MESSAGE = "error_message";

    /** Atributo del contador de backlog. */
    static final String TOTAL = "total";

    /**
     * Sufijo que cierra un rango de fechas por arriba.
     *
     * <p>La clave de orden es {@code fecha#event_id}, asi que comparar contra la fecha
     * a secas dejaria fuera todos los eventos de ese instante. Con el codepoint mas alto
     * detras, el extremo superior queda por encima de cualquier identificador posible.
     */
    static final String SK_MAX = "#￿";

    /** Sufijo que abre un rango de fechas por abajo, antes de cualquier identificador. */
    static final String SK_MIN = "#";

    /**
     * Formato de instante de ancho fijo, con nueve decimales siempre presentes.
     *
     * <p>No sirve {@code Instant.toString()}: omite los decimales cuando son cero, y al
     * comparar como texto {@code ...:00.500Z} queda antes que {@code ...:00Z} porque el
     * punto es menor que la Z. Como este texto es la clave de orden del indice, esa
     * diferencia invertiria el orden de la pagina.
     */
    private static final DateTimeFormatter SORTABLE = new DateTimeFormatterBuilder()
            .appendInstant(9)
            .toFormatter();

    private NotificationTable() {
    }

    static String eventPk(String eventId) {
        return "EVENT#" + eventId;
    }

    static String clientPk(String clientId) {
        return "CLIENT#" + clientId;
    }

    /**
     * Clave de orden del indice: fecha de creacion mas {@code event_id}.
     *
     * <p>El desempate por identificador replica lo que hacia el {@code ORDER BY} en
     * Postgres: sin el, dos eventos creados en el mismo instante podrian saltar entre
     * paginas contiguas.
     */
    static String clientSk(Instant createdAt, String eventId) {
        return timestamp(createdAt) + "#" + eventId;
    }

    /**
     * Clave de orden de un intento.
     *
     * <p>Lleva el numero de reenvio delante porque cada reenvio reinicia el contador de
     * intentos: ordenar solo por numero de intento mezclaria el intento 1 del segundo
     * ciclo con el 1 del primero. Ambos numeros van a ancho fijo para que el orden de
     * texto coincida con el cronologico.
     */
    static String attemptSk(int replayCount, int attemptNumber) {
        return ATTEMPT_PREFIX + "%04d#%04d".formatted(replayCount, attemptNumber);
    }

    static String statusSk(String status) {
        return STATUS_PREFIX + status;
    }

    static String timestamp(Instant value) {
        return SORTABLE.format(value);
    }

    static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of(PK, s(pk), SK, s(sk));
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    /** Los opcionales se omiten del item en vez de guardarse como NULL. */
    static void putIfPresent(Map<String, AttributeValue> item, String name, String value) {
        if (value != null) {
            item.put(name, s(value));
        }
    }

    static void putIfPresent(Map<String, AttributeValue> item, String name, Integer value) {
        if (value != null) {
            item.put(name, n(value));
        }
    }

    static void putTimestampIfPresent(Map<String, AttributeValue> item, String name, Instant value) {
        if (value != null) {
            item.put(name, s(timestamp(value)));
        }
    }

    static String string(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    static Integer integer(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : Integer.valueOf(value.n());
    }

    static long longValue(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? 0L : Long.parseLong(value.n());
    }

    static Instant instant(Map<String, AttributeValue> item, String name) {
        String value = string(item, name);
        return value == null ? null : Instant.parse(value);
    }
}
