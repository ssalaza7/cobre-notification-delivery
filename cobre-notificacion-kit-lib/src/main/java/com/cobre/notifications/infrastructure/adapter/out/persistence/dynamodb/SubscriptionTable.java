package com.cobre.notifications.infrastructure.adapter.out.persistence.dynamodb;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.cobre.notifications.domain.model.Subscription;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Esquema de la tabla de suscripciones.
 *
 * <p>Una particion por cliente y una clave de orden por tipo de evento. De ahi salen
 * dos propiedades sin escribir codigo: todas las suscripciones de un cliente se leen en
 * una consulta, y no puede haber dos para el mismo tipo, que en el modelo relacional
 * exigia un indice unico parcial sobre las filas activas.
 */
final class SubscriptionTable {

    static final String PK = "pk";
    static final String SK = "sk";

    static final String ID = "id";
    static final String CLIENT_ID = "client_id";
    static final String EVENT_TYPE = "event_type";
    static final String WEBHOOK_URL = "webhook_url";
    /** Referencia al secreto en el almacen. El valor no vive aqui. */
    static final String SECRET_REF = "secret_ref";
    static final String ACTIVE = "active";

    private static final String SUB_PREFIX = "SUB#";

    private SubscriptionTable() {
    }

    static String clientPk(String clientId) {
        return "CLIENT#" + clientId;
    }

    static String subSk(String eventType) {
        return SUB_PREFIX + eventType;
    }

    static String prefix() {
        return SUB_PREFIX;
    }

    static Map<String, AttributeValue> toItem(Subscription subscription) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, NotificationTable.s(clientPk(subscription.clientId())));
        item.put(SK, NotificationTable.s(subSk(subscription.eventType())));
        item.put(ID, NotificationTable.s(subscription.id().toString()));
        item.put(CLIENT_ID, NotificationTable.s(subscription.clientId()));
        item.put(EVENT_TYPE, NotificationTable.s(subscription.eventType()));
        item.put(WEBHOOK_URL, NotificationTable.s(subscription.webhookUrl()));
        item.put(ACTIVE, AttributeValue.fromBool(subscription.active()));
        return item;
    }

    static String secretRef(Map<String, AttributeValue> item) {
        return NotificationTable.string(item, SECRET_REF);
    }

    /** El secreto lo aporta quien llama, tras leerlo del almacen. */
    static Subscription toDomain(Map<String, AttributeValue> item, String signingSecret) {
        AttributeValue active = item.get(ACTIVE);
        return new Subscription(
                UUID.fromString(NotificationTable.string(item, ID)),
                NotificationTable.string(item, CLIENT_ID),
                NotificationTable.string(item, EVENT_TYPE),
                NotificationTable.string(item, WEBHOOK_URL),
                signingSecret,
                active != null && Boolean.TRUE.equals(active.bool()));
    }
}
