package com.cobre.notifications.infrastructure.adapter.out.persistence.dynamodb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.domain.model.DeliveryStatus;
import com.cobre.notifications.domain.model.EventQuery;
import com.cobre.notifications.domain.model.NotificationEvent;
import com.cobre.notifications.domain.model.PageResult;
import com.cobre.notifications.domain.model.StatusCount;
import com.cobre.notifications.infrastructure.config.DynamoDbProperties;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * Almacen de notificaciones sobre DynamoDB.
 *
 * <p>Cada evento vive en el item {@code EVENT#{id} / META}. La escritura condicional
 * sustituye a lo que en Postgres eran el {@code ON CONFLICT DO NOTHING} de la ingesta y
 * el {@code WHERE attempts = ... AND replay_count = ...} del bloqueo optimista: en los
 * dos casos la condicion la evalua el motor sobre el item, sin leerlo antes, asi que dos
 * ejecuciones simultaneas siguen sin poder pisarse.
 *
 * <p>Ingesta y transicion van dentro de una transaccion porque tambien mueven el conteo
 * por estado. Si el contador se actualizara aparte, un fallo entre ambas escrituras lo
 * dejaria desviado para siempre, y un gauge de backlog desviado es una alarma que no
 * suena o que no se apaga.
 */
@Repository
@DependsOn("dynamoDbTableInitializer")
public class DynamoDbNotificationEventRepositoryAdapter implements NotificationEventRepositoryPort {

    private static final String CONDITION_FAILED = "ConditionalCheckFailed";

    /**
     * Tope de vueltas al indice para llenar una pagina.
     *
     * <p>El filtro por estado se aplica despues de leer, asi que una pagina puede volver
     * corta y hacer falta otra consulta. El tope acota cuanto puede tardar una peticion
     * cuando el estado buscado es muy poco frecuente: antes que esperar indefinidamente,
     * se devuelve una pagina corta con su cursor y el cliente sigue paginando.
     */
    private static final int MAX_QUERY_ROUNDS = 5;

    private final DynamoDbAsyncClient dynamo;
    private final DynamoDbProperties properties;

    public DynamoDbNotificationEventRepositoryAdapter(
            DynamoDbAsyncClient dynamo, DynamoDbProperties properties) {
        this.dynamo = dynamo;
        this.properties = properties;
    }

    @Override
    public Mono<Boolean> insertIfAbsent(NotificationEvent event) {
        TransactWriteItem put = TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(properties.tableName())
                        .item(EventItem.toItem(event))
                        // Es lo que hace idempotente la ingesta: si el broker reentrega
                        // el mismo evento, la segunda escritura no entra.
                        .conditionExpression("attribute_not_exists(#pk)")
                        .expressionAttributeNames(Map.of("#pk", NotificationTable.PK))
                        .build())
                .build();

        return transact(List.of(put, counterDelta(event.deliveryStatus(), 1)))
                .thenReturn(true)
                .onErrorResume(DynamoDbNotificationEventRepositoryAdapter::isConditionFailure,
                        error -> Mono.just(false));
    }

    @Override
    public Mono<NotificationEvent> update(NotificationEvent previous, NotificationEvent updated) {
        List<TransactWriteItem> items = new ArrayList<>();
        items.add(stateTransition(previous, updated));

        // Un reintento puede dejar el evento en el mismo estado (RETRYING -> RETRYING).
        // Ahi el conteo no se mueve y no hay nada que sumar ni restar.
        if (previous.deliveryStatus() != updated.deliveryStatus()) {
            items.add(counterDelta(previous.deliveryStatus(), -1));
            items.add(counterDelta(updated.deliveryStatus(), 1));
        }

        return transact(items)
                .thenReturn(updated)
                .onErrorResume(DynamoDbNotificationEventRepositoryAdapter::isConditionFailure,
                        error -> Mono.empty());
    }

    @Override
    public Mono<NotificationEvent> findById(String eventId) {
        return Mono.fromFuture(() -> dynamo.getItem(request -> request
                        .tableName(properties.tableName())
                        .key(NotificationTable.key(
                                NotificationTable.eventPk(eventId), NotificationTable.META))
                        // Lectura consistente: el worker lee el evento justo despues de
                        // que otra instancia lo escribiera, y una replica atrasada le
                        // daria un estado ya superado.
                        .consistentRead(true)))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> EventItem.toDomain(response.item()));
    }

    @Override
    public Mono<NotificationEvent> findByIdAndClientId(String eventId, String clientId) {
        return findById(eventId).filter(event -> event.belongsTo(clientId));
    }

    @Override
    public Mono<PageResult<NotificationEvent>> search(EventQuery query) {
        String clientPk = NotificationTable.clientPk(query.clientId());
        Map<String, AttributeValue> start = query.isFirstPage()
                ? null
                : EventCursor.decode(query.cursor(), clientPk);

        return queryPage(query, clientPk, start, new ArrayList<>(), MAX_QUERY_ROUNDS);
    }

    @Override
    public Flux<StatusCount> countByStatus() {
        List<Map<String, AttributeValue>> keys = new ArrayList<>();
        for (int shard = 0; shard < properties.counterShards(); shard++) {
            for (DeliveryStatus status : DeliveryStatus.values()) {
                keys.add(NotificationTable.key(counterPk(shard), NotificationTable.statusSk(status.name())));
            }
        }

        return Mono.fromFuture(() -> dynamo.batchGetItem(BatchGetItemRequest.builder()
                        .requestItems(Map.of(properties.tableName(), KeysAndAttributes.builder()
                                .keys(keys)
                                .consistentRead(true)
                                .build()))
                        .build()))
                .flatMapIterable(response -> {
                    Map<DeliveryStatus, Long> totals = new LinkedHashMap<>();
                    for (Map<String, AttributeValue> item : response.responses()
                            .getOrDefault(properties.tableName(), List.of())) {
                        String sk = NotificationTable.string(item, NotificationTable.SK);
                        DeliveryStatus status = DeliveryStatus.valueOf(
                                sk.substring(NotificationTable.STATUS_PREFIX.length()));
                        totals.merge(status, NotificationTable.longValue(item, NotificationTable.TOTAL), Long::sum);
                    }
                    return totals.entrySet().stream()
                            .map(entry -> new StatusCount(entry.getKey(), entry.getValue()))
                            .toList();
                });
    }

    /**
     * Consulta el indice y acumula hasta llenar la pagina.
     *
     * <p>Se repite porque el filtro por estado descarta items ya leidos: DynamoDB cuenta
     * el limite antes de filtrar, de modo que una consulta puede devolver menos de lo
     * pedido aunque queden resultados mas atras.
     */
    private Mono<PageResult<NotificationEvent>> queryPage(
            EventQuery query,
            String clientPk,
            Map<String, AttributeValue> start,
            List<NotificationEvent> accumulated,
            int roundsLeft) {

        int missing = query.size() - accumulated.size();

        return Mono.fromFuture(() -> dynamo.query(buildQuery(query, clientPk, start, missing)))
                .flatMap(response -> {
                    response.items().stream()
                            .map(EventItem::toDomain)
                            .forEach(accumulated::add);

                    Map<String, AttributeValue> next = response.lastEvaluatedKey();
                    boolean exhausted = next == null || next.isEmpty();

                    if (exhausted) {
                        return Mono.just(new PageResult<>(accumulated, query.size(), null));
                    }
                    if (accumulated.size() >= query.size() || roundsLeft <= 1) {
                        return Mono.just(new PageResult<>(
                                accumulated, query.size(), EventCursor.encode(next)));
                    }
                    return queryPage(query, clientPk, next, accumulated, roundsLeft - 1);
                });
    }

    private QueryRequest buildQuery(
            EventQuery query, String clientPk, Map<String, AttributeValue> start, int limit) {

        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();
        names.put("#gpk", NotificationTable.GSI_PK);
        values.put(":client", NotificationTable.s(clientPk));

        StringBuilder condition = new StringBuilder("#gpk = :client");
        if (query.createdFrom() != null || query.createdTo() != null) {
            names.put("#gsk", NotificationTable.GSI_SK);
            // La clave de orden es "fecha#event_id". Para acotar por fecha se comparan
            // los extremos con el separador ya incluido, de modo que la comparacion de
            // texto abarque todos los identificadores de ese instante.
            AttributeValue from = query.createdFrom() == null ? null : NotificationTable.s(
                    NotificationTable.timestamp(query.createdFrom()) + NotificationTable.SK_MIN);
            AttributeValue to = query.createdTo() == null ? null : NotificationTable.s(
                    NotificationTable.timestamp(query.createdTo()) + NotificationTable.SK_MAX);

            if (from != null && to != null) {
                condition.append(" AND #gsk BETWEEN :from AND :to");
                values.put(":from", from);
                values.put(":to", to);
            } else if (from != null) {
                condition.append(" AND #gsk >= :from");
                values.put(":from", from);
            } else {
                condition.append(" AND #gsk <= :to");
                values.put(":to", to);
            }
        }

        QueryRequest.Builder request = QueryRequest.builder()
                .tableName(properties.tableName())
                .indexName(properties.clientIndex())
                .keyConditionExpression(condition.toString())
                // De la mas reciente a la mas antigua, como el listado en Postgres.
                .scanIndexForward(false)
                .limit(limit)
                .exclusiveStartKey(start);

        if (query.deliveryStatus() != null) {
            names.put("#status", NotificationTable.DELIVERY_STATUS);
            values.put(":status", NotificationTable.s(query.deliveryStatus().name()));
            request.filterExpression("#status = :status");
        }

        return request.expressionAttributeNames(names).expressionAttributeValues(values).build();
    }

    /** Escritura condicional del estado. Es el bloqueo optimista. */
    private TransactWriteItem stateTransition(NotificationEvent previous, NotificationEvent updated) {
        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();
        List<String> sets = new ArrayList<>();
        List<String> removes = new ArrayList<>();

        // client_id, event_type, content y created_at son inmutables una vez ingerido el
        // evento: no participan de la actualizacion.
        assign(sets, removes, names, values, NotificationTable.DELIVERY_STATUS,
                NotificationTable.s(updated.deliveryStatus().name()));
        assign(sets, removes, names, values, NotificationTable.DELIVERY_DATE,
                updated.deliveryDate() == null
                        ? null
                        : NotificationTable.s(NotificationTable.timestamp(updated.deliveryDate())));
        assign(sets, removes, names, values, NotificationTable.ATTEMPTS,
                NotificationTable.n(updated.attempts()));
        assign(sets, removes, names, values, NotificationTable.REPLAY_COUNT,
                NotificationTable.n(updated.replayCount()));
        assign(sets, removes, names, values, NotificationTable.WEBHOOK_URL,
                updated.webhookUrl() == null ? null : NotificationTable.s(updated.webhookUrl()));
        assign(sets, removes, names, values, NotificationTable.LAST_HTTP_STATUS,
                updated.lastHttpStatus() == null ? null : NotificationTable.n(updated.lastHttpStatus()));
        assign(sets, removes, names, values, NotificationTable.LAST_ERROR,
                updated.lastError() == null ? null : NotificationTable.s(updated.lastError()));
        assign(sets, removes, names, values, NotificationTable.UPDATED_AT,
                NotificationTable.s(NotificationTable.timestamp(updated.updatedAt())));

        StringBuilder expression = new StringBuilder("SET " + String.join(", ", sets));
        if (!removes.isEmpty()) {
            expression.append(" REMOVE ").append(String.join(", ", removes));
        }

        names.put("#expAttempts", NotificationTable.ATTEMPTS);
        names.put("#expReplay", NotificationTable.REPLAY_COUNT);
        values.put(":expAttempts", NotificationTable.n(previous.attempts()));
        values.put(":expReplay", NotificationTable.n(previous.replayCount()));

        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(properties.tableName())
                        .key(NotificationTable.key(
                                NotificationTable.eventPk(updated.eventId()), NotificationTable.META))
                        .updateExpression(expression.toString())
                        .conditionExpression("#expAttempts = :expAttempts AND #expReplay = :expReplay")
                        .expressionAttributeNames(names)
                        .expressionAttributeValues(values)
                        .build())
                .build();
    }

    /**
     * Suma o resta uno al conteo de un estado.
     *
     * <p>Va a una particion elegida al azar entre varias. Todas las transiciones del
     * sistema pasan por aqui, y con un unico item de contador cada escritura del flujo
     * competiria por la misma particion, que es el limite duro de DynamoDB. Al leer se
     * suman las particiones.
     */
    private TransactWriteItem counterDelta(DeliveryStatus status, long delta) {
        int shard = ThreadLocalRandom.current().nextInt(properties.counterShards());

        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(properties.tableName())
                        .key(NotificationTable.key(
                                counterPk(shard), NotificationTable.statusSk(status.name())))
                        .updateExpression("ADD #total :delta")
                        .expressionAttributeNames(Map.of("#total", NotificationTable.TOTAL))
                        .expressionAttributeValues(Map.of(":delta", NotificationTable.n(delta)))
                        .build())
                .build();
    }

    private String counterPk(int shard) {
        return "STATS#" + shard;
    }

    private Mono<Void> transact(List<TransactWriteItem> items) {
        return Mono.fromFuture(() -> dynamo.transactWriteItems(
                        TransactWriteItemsRequest.builder().transactItems(items).build()))
                .then();
    }

    private static void assign(
            List<String> sets,
            List<String> removes,
            Map<String, String> names,
            Map<String, AttributeValue> values,
            String attribute,
            AttributeValue value) {

        String placeholder = "#" + attribute;
        names.put(placeholder, attribute);
        // Los opcionales que quedan vacios se borran del item en vez de guardarse como
        // NULL: un atributo ausente no ocupa ni aparece al consultarlo.
        if (value == null) {
            removes.add(placeholder);
        } else {
            values.put(":" + attribute, value);
            sets.add(placeholder + " = :" + attribute);
        }
    }

    /**
     * Distingue "otra ejecucion llego antes" de un fallo real.
     *
     * <p>Una transaccion cancelada por condicion no es un error: significa que el item
     * ya existia, o que la version con la que se leyo quedo obsoleta. Cualquier otro
     * motivo de cancelacion —capacidad, conflicto entre transacciones— si tiene que
     * propagarse para que el mensaje se reintente.
     */
    private static boolean isConditionFailure(Throwable error) {
        if (!(error instanceof TransactionCanceledException canceled)) {
            return false;
        }
        return canceled.cancellationReasons().stream()
                .map(CancellationReason::code)
                .anyMatch(CONDITION_FAILED::equals);
    }
}
