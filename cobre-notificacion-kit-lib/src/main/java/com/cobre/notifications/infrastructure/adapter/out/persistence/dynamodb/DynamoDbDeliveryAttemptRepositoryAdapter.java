package com.cobre.notifications.infrastructure.adapter.out.persistence.dynamodb;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.cobre.notifications.application.port.out.DeliveryAttemptRepositoryPort;
import com.cobre.notifications.domain.model.AttemptOutcome;
import com.cobre.notifications.domain.model.DeliveryAttempt;
import com.cobre.notifications.infrastructure.config.DynamoDbProperties;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Bitacora de intentos de entrega. Solo inserta y consulta; nunca modifica.
 *
 * <p>Cada intento es un item propio en la particion de su evento, asi que el detalle de
 * la API lee evento y bitacora en una sola consulta.
 *
 * <p>Los intentos caducan por TTL y el evento no. La bitacora existe para responder una
 * queja concreta —cuando se intento, que contesto el destino, cuanto tardo— y esa
 * pregunta llega en dias, no en anos; el estado final de la notificacion, en cambio, es
 * lo que el cliente puede consultar siempre. Guardar los intentos para siempre seria
 * pagar almacenamiento indefinido por un dato que deja de consultarse.
 */
@Repository
@DependsOn("dynamoDbTableInitializer")
public class DynamoDbDeliveryAttemptRepositoryAdapter implements DeliveryAttemptRepositoryPort {

    private final DynamoDbAsyncClient dynamo;
    private final DynamoDbProperties properties;

    public DynamoDbDeliveryAttemptRepositoryAdapter(
            DynamoDbAsyncClient dynamo, DynamoDbProperties properties) {
        this.dynamo = dynamo;
        this.properties = properties;
    }

    @Override
    public Mono<DeliveryAttempt> append(DeliveryAttempt attempt) {
        return Mono.fromFuture(() -> dynamo.putItem(request -> request
                        .tableName(properties.tableName())
                        .item(toItem(attempt))))
                .thenReturn(attempt);
    }

    @Override
    public Flux<DeliveryAttempt> findByEventId(String eventId) {
        return Mono.fromFuture(() -> dynamo.query(request -> request
                        .tableName(properties.tableName())
                        .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                        .expressionAttributeNames(Map.of(
                                "#pk", NotificationTable.PK,
                                "#sk", NotificationTable.SK))
                        .expressionAttributeValues(Map.of(
                                ":pk", NotificationTable.s(NotificationTable.eventPk(eventId)),
                                ":prefix", NotificationTable.s(NotificationTable.ATTEMPT_PREFIX)))
                        // Del mas reciente al mas antiguo: la clave de orden lleva
                        // delante el numero de reenvio, asi que el orden descendente
                        // cruza bien los ciclos de entrega.
                        .scanIndexForward(false)
                        .consistentRead(true)))
                .flatMapIterable(response -> response.items().stream().map(this::toDomain).toList());
    }

    private Map<String, AttributeValue> toItem(DeliveryAttempt attempt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(NotificationTable.PK,
                NotificationTable.s(NotificationTable.eventPk(attempt.eventId())));
        item.put(NotificationTable.SK, NotificationTable.s(
                NotificationTable.attemptSk(attempt.replayCount(), attempt.attemptNumber())));

        item.put(NotificationTable.ATTEMPT_ID, NotificationTable.s(attempt.id().toString()));
        item.put(NotificationTable.EVENT_ID, NotificationTable.s(attempt.eventId()));
        item.put(NotificationTable.ATTEMPT_NUMBER, NotificationTable.n(attempt.attemptNumber()));
        item.put(NotificationTable.REPLAY_COUNT, NotificationTable.n(attempt.replayCount()));
        item.put(NotificationTable.ATTEMPTED_AT,
                NotificationTable.s(NotificationTable.timestamp(attempt.attemptedAt())));
        item.put(NotificationTable.OUTCOME, NotificationTable.s(attempt.outcome().name()));
        item.put(NotificationTable.DURATION_MS, NotificationTable.n(attempt.durationMs()));
        item.put(NotificationTable.TTL,
                NotificationTable.n(attempt.attemptedAt().plus(properties.attemptTtl()).getEpochSecond()));

        NotificationTable.putIfPresent(item, NotificationTable.HTTP_STATUS, attempt.httpStatus());
        NotificationTable.putIfPresent(item, NotificationTable.ERROR_MESSAGE, attempt.errorMessage());
        return item;
    }

    private DeliveryAttempt toDomain(Map<String, AttributeValue> item) {
        return new DeliveryAttempt(
                UUID.fromString(NotificationTable.string(item, NotificationTable.ATTEMPT_ID)),
                NotificationTable.string(item, NotificationTable.EVENT_ID),
                NotificationTable.integer(item, NotificationTable.ATTEMPT_NUMBER),
                NotificationTable.integer(item, NotificationTable.REPLAY_COUNT),
                NotificationTable.instant(item, NotificationTable.ATTEMPTED_AT),
                AttemptOutcome.valueOf(NotificationTable.string(item, NotificationTable.OUTCOME)),
                NotificationTable.integer(item, NotificationTable.HTTP_STATUS),
                NotificationTable.longValue(item, NotificationTable.DURATION_MS),
                NotificationTable.string(item, NotificationTable.ERROR_MESSAGE));
    }
}
