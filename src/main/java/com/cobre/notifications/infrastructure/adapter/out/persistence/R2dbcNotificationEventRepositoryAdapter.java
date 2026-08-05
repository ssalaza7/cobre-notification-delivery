package com.cobre.notifications.infrastructure.adapter.out.persistence;

import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.domain.model.DeliveryStatus;
import com.cobre.notifications.domain.model.EventQuery;
import com.cobre.notifications.domain.model.EventVersion;
import com.cobre.notifications.domain.model.NotificationEvent;
import com.cobre.notifications.domain.model.StatusCount;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.cobre.notifications.infrastructure.adapter.out.persistence.SqlBindings.TypedValue;
import static com.cobre.notifications.infrastructure.adapter.out.persistence.SqlBindings.bindAll;
import static com.cobre.notifications.infrastructure.adapter.out.persistence.SqlBindings.toDomain;

/**
 * Adaptador de persistencia sobre R2DBC.
 *
 * <p>Usa {@code DatabaseClient} en vez de repositorios de Spring Data por dos
 * razones concretas: el UPDATE con bloqueo optimista necesita conocer cuantas filas
 * afecto, y los filtros de la API se arman dinamicamente. Ambas cosas contra un
 * repositorio derivado terminan en anotaciones {@code @Query} igual de explicitas,
 * pero con una capa mas de indireccion.
 */
@Repository
public class R2dbcNotificationEventRepositoryAdapter implements NotificationEventRepositoryPort {

    private static final String COLUMNS = """
            event_id, client_id, event_type, content, created_at, delivery_status,
            delivery_date, attempts, replay_count, webhook_url, last_http_status,
            last_error, updated_at
            """;

    private final DatabaseClient db;

    public R2dbcNotificationEventRepositoryAdapter(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Boolean> insertIfAbsent(NotificationEvent event) {
        String sql = """
                INSERT INTO notification_event (
                    event_id, client_id, event_type, content, created_at, delivery_status,
                    delivery_date, attempts, replay_count, webhook_url, last_http_status,
                    last_error, updated_at
                ) VALUES (
                    :eventId, :clientId, :eventType, :content, :createdAt, :deliveryStatus,
                    :deliveryDate, :attempts, :replayCount, :webhookUrl, :lastHttpStatus,
                    :lastError, :updatedAt
                )
                ON CONFLICT (event_id) DO NOTHING
                """;

        return bindAll(db.sql(sql), stateParams(event))
                .fetch()
                .rowsUpdated()
                .map(rows -> rows > 0);
    }

    @Override
    public Mono<NotificationEvent> update(NotificationEvent event, EventVersion expected) {
        String sql = """
                UPDATE notification_event SET
                    delivery_status  = :deliveryStatus,
                    delivery_date    = :deliveryDate,
                    attempts         = :attempts,
                    replay_count     = :replayCount,
                    webhook_url      = :webhookUrl,
                    last_http_status = :lastHttpStatus,
                    last_error       = :lastError,
                    updated_at       = :updatedAt
                WHERE event_id     = :eventId
                  AND attempts     = :expectedAttempts
                  AND replay_count = :expectedReplayCount
                """;

        // client_id, event_type, content y created_at son inmutables una vez ingerido
        // el evento: no participan del UPDATE.
        Map<String, TypedValue> params = new LinkedHashMap<>();
        params.put("eventId", TypedValue.of(event.eventId()));
        params.put("deliveryStatus", TypedValue.of(event.deliveryStatus().name()));
        params.put("deliveryDate", TypedValue.timestamp(event.deliveryDate()));
        params.put("attempts", TypedValue.of(event.attempts()));
        params.put("replayCount", TypedValue.of(event.replayCount()));
        params.put("webhookUrl", TypedValue.of(event.webhookUrl()));
        params.put("lastHttpStatus", TypedValue.of(event.lastHttpStatus()));
        params.put("lastError", TypedValue.of(event.lastError()));
        params.put("updatedAt", TypedValue.timestamp(event.updatedAt()));
        params.put("expectedAttempts", TypedValue.of(expected.attempts()));
        params.put("expectedReplayCount", TypedValue.of(expected.replayCount()));

        return bindAll(db.sql(sql), params)
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows > 0 ? Mono.just(event) : Mono.empty());
    }

    @Override
    public Mono<NotificationEvent> findById(String eventId) {
        return db.sql("SELECT " + COLUMNS + " FROM notification_event WHERE event_id = :eventId")
                .bind("eventId", eventId)
                .map(R2dbcNotificationEventRepositoryAdapter::toDomainEvent)
                .one();
    }

    @Override
    public Mono<NotificationEvent> findByIdAndClientId(String eventId, String clientId) {
        return db.sql("SELECT " + COLUMNS
                        + " FROM notification_event WHERE event_id = :eventId AND client_id = :clientId")
                .bind("eventId", eventId)
                .bind("clientId", clientId)
                .map(R2dbcNotificationEventRepositoryAdapter::toDomainEvent)
                .one();
    }

    @Override
    public Flux<NotificationEvent> search(EventQuery query) {
        Map<String, TypedValue> params = new LinkedHashMap<>();
        String where = buildWhere(query, params);
        params.put("limit", TypedValue.of(query.size()));
        params.put("offset", TypedValue.of(query.offset()));

        // El desempate por event_id evita que dos filas con el mismo created_at
        // salten entre paginas contiguas.
        String sql = "SELECT " + COLUMNS + " FROM notification_event " + where
                + " ORDER BY created_at DESC, event_id DESC LIMIT :limit OFFSET :offset";

        return bindAll(db.sql(sql), params)
                .map(R2dbcNotificationEventRepositoryAdapter::toDomainEvent)
                .all();
    }

    @Override
    public Mono<Long> count(EventQuery query) {
        Map<String, TypedValue> params = new LinkedHashMap<>();
        String where = buildWhere(query, params);

        return bindAll(db.sql("SELECT COUNT(*) AS total FROM notification_event " + where), params)
                .map(row -> row.get("total", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    @Override
    public Flux<StatusCount> countByStatus() {
        return db.sql("SELECT delivery_status, COUNT(*) AS total FROM notification_event GROUP BY delivery_status")
                .map(row -> new StatusCount(
                        DeliveryStatus.valueOf(row.get("delivery_status", String.class)),
                        row.get("total", Long.class)))
                .all();
    }

    /**
     * Arma el WHERE con solo los filtros presentes. Los fragmentos son literales del
     * codigo y los valores van enlazados; la entrada del usuario nunca toca el SQL.
     */
    private String buildWhere(EventQuery query, Map<String, TypedValue> params) {
        StringBuilder where = new StringBuilder("WHERE client_id = :clientId");
        params.put("clientId", TypedValue.of(query.clientId()));

        if (query.createdFrom() != null) {
            where.append(" AND created_at >= :createdFrom");
            params.put("createdFrom", TypedValue.timestamp(query.createdFrom()));
        }
        if (query.createdTo() != null) {
            where.append(" AND created_at <= :createdTo");
            params.put("createdTo", TypedValue.timestamp(query.createdTo()));
        }
        if (query.deliveryStatus() != null) {
            where.append(" AND delivery_status = :deliveryStatus");
            params.put("deliveryStatus", TypedValue.of(query.deliveryStatus().name()));
        }
        return where.toString();
    }

    private Map<String, TypedValue> stateParams(NotificationEvent event) {
        Map<String, TypedValue> params = new LinkedHashMap<>();
        params.put("eventId", TypedValue.of(event.eventId()));
        params.put("clientId", TypedValue.of(event.clientId()));
        params.put("eventType", TypedValue.of(event.eventType()));
        params.put("content", TypedValue.of(event.content()));
        params.put("createdAt", TypedValue.timestamp(event.createdAt()));
        params.put("deliveryStatus", TypedValue.of(event.deliveryStatus().name()));
        params.put("deliveryDate", TypedValue.timestamp(event.deliveryDate()));
        params.put("attempts", TypedValue.of(event.attempts()));
        params.put("replayCount", TypedValue.of(event.replayCount()));
        params.put("webhookUrl", TypedValue.of(event.webhookUrl()));
        params.put("lastHttpStatus", TypedValue.of(event.lastHttpStatus()));
        params.put("lastError", TypedValue.of(event.lastError()));
        params.put("updatedAt", TypedValue.timestamp(event.updatedAt()));
        return params;
    }

    private static NotificationEvent toDomainEvent(Readable row) {
        return new NotificationEvent(
                row.get("event_id", String.class),
                row.get("client_id", String.class),
                row.get("event_type", String.class),
                row.get("content", String.class),
                toDomain(row.get("created_at", OffsetDateTime.class)),
                DeliveryStatus.valueOf(row.get("delivery_status", String.class)),
                toDomain(row.get("delivery_date", OffsetDateTime.class)),
                row.get("attempts", Integer.class),
                row.get("replay_count", Integer.class),
                row.get("webhook_url", String.class),
                row.get("last_http_status", Integer.class),
                row.get("last_error", String.class),
                toDomain(row.get("updated_at", OffsetDateTime.class)));
    }
}
