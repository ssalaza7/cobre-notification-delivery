package com.cobre.notifications.infrastructure.adapter.out.persistence.dynamodb;

import java.util.HashMap;
import java.util.Map;

import com.cobre.notifications.domain.model.DeliveryStatus;
import com.cobre.notifications.domain.model.NotificationEvent;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Traduce el evento entre el dominio y el item {@code META} de la tabla. */
final class EventItem {

    private EventItem() {
    }

    static Map<String, AttributeValue> toItem(NotificationEvent event) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(NotificationTable.PK, NotificationTable.s(NotificationTable.eventPk(event.eventId())));
        item.put(NotificationTable.SK, NotificationTable.s(NotificationTable.META));

        // Claves del indice por cliente y fecha. Solo las lleva este item: los intentos
        // no aparecen en el listado, y sin estos atributos quedan fuera del indice sin
        // necesidad de filtrarlos.
        item.put(NotificationTable.GSI_PK, NotificationTable.s(NotificationTable.clientPk(event.clientId())));
        item.put(NotificationTable.GSI_SK,
                NotificationTable.s(NotificationTable.clientSk(event.createdAt(), event.eventId())));

        item.put(NotificationTable.EVENT_ID, NotificationTable.s(event.eventId()));
        item.put(NotificationTable.CLIENT_ID, NotificationTable.s(event.clientId()));
        item.put(NotificationTable.EVENT_TYPE, NotificationTable.s(event.eventType()));
        item.put(NotificationTable.CONTENT, NotificationTable.s(event.content()));
        item.put(NotificationTable.CREATED_AT, NotificationTable.s(NotificationTable.timestamp(event.createdAt())));
        item.put(NotificationTable.DELIVERY_STATUS, NotificationTable.s(event.deliveryStatus().name()));
        item.put(NotificationTable.ATTEMPTS, NotificationTable.n(event.attempts()));
        item.put(NotificationTable.REPLAY_COUNT, NotificationTable.n(event.replayCount()));
        item.put(NotificationTable.UPDATED_AT, NotificationTable.s(NotificationTable.timestamp(event.updatedAt())));

        NotificationTable.putTimestampIfPresent(item, NotificationTable.DELIVERY_DATE, event.deliveryDate());
        NotificationTable.putIfPresent(item, NotificationTable.WEBHOOK_URL, event.webhookUrl());
        NotificationTable.putIfPresent(item, NotificationTable.LAST_HTTP_STATUS, event.lastHttpStatus());
        NotificationTable.putIfPresent(item, NotificationTable.LAST_ERROR, event.lastError());
        return item;
    }

    static NotificationEvent toDomain(Map<String, AttributeValue> item) {
        return new NotificationEvent(
                NotificationTable.string(item, NotificationTable.EVENT_ID),
                NotificationTable.string(item, NotificationTable.CLIENT_ID),
                NotificationTable.string(item, NotificationTable.EVENT_TYPE),
                NotificationTable.string(item, NotificationTable.CONTENT),
                NotificationTable.instant(item, NotificationTable.CREATED_AT),
                DeliveryStatus.valueOf(NotificationTable.string(item, NotificationTable.DELIVERY_STATUS)),
                NotificationTable.instant(item, NotificationTable.DELIVERY_DATE),
                NotificationTable.integer(item, NotificationTable.ATTEMPTS),
                NotificationTable.integer(item, NotificationTable.REPLAY_COUNT),
                NotificationTable.string(item, NotificationTable.WEBHOOK_URL),
                NotificationTable.integer(item, NotificationTable.LAST_HTTP_STATUS),
                NotificationTable.string(item, NotificationTable.LAST_ERROR),
                NotificationTable.instant(item, NotificationTable.UPDATED_AT));
    }
}
