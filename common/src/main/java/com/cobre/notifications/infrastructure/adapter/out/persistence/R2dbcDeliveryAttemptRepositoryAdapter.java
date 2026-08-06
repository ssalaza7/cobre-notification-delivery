package com.cobre.notifications.infrastructure.adapter.out.persistence;

import com.cobre.notifications.application.port.out.DeliveryAttemptRepositoryPort;
import com.cobre.notifications.domain.model.AttemptOutcome;
import com.cobre.notifications.domain.model.DeliveryAttempt;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.cobre.notifications.infrastructure.adapter.out.persistence.SqlBindings.TypedValue;
import static com.cobre.notifications.infrastructure.adapter.out.persistence.SqlBindings.bindAll;
import static com.cobre.notifications.infrastructure.adapter.out.persistence.SqlBindings.toDomain;

/** Bitacora append-only de intentos de entrega. Solo inserta y consulta; nunca modifica. */
@Repository
public class R2dbcDeliveryAttemptRepositoryAdapter implements DeliveryAttemptRepositoryPort {

    private final DatabaseClient db;

    public R2dbcDeliveryAttemptRepositoryAdapter(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<DeliveryAttempt> append(DeliveryAttempt attempt) {
        String sql = """
                INSERT INTO delivery_attempt (
                    id, event_id, attempt_number, replay_count, attempted_at,
                    outcome, http_status, duration_ms, error_message
                ) VALUES (
                    :id, :eventId, :attemptNumber, :replayCount, :attemptedAt,
                    :outcome, :httpStatus, :durationMs, :errorMessage
                )
                """;

        Map<String, TypedValue> params = new LinkedHashMap<>();
        params.put("id", new TypedValue(attempt.id(), UUID.class));
        params.put("eventId", TypedValue.of(attempt.eventId()));
        params.put("attemptNumber", TypedValue.of(attempt.attemptNumber()));
        params.put("replayCount", TypedValue.of(attempt.replayCount()));
        params.put("attemptedAt", TypedValue.timestamp(attempt.attemptedAt()));
        params.put("outcome", TypedValue.of(attempt.outcome().name()));
        params.put("httpStatus", TypedValue.of(attempt.httpStatus()));
        params.put("durationMs", TypedValue.of(attempt.durationMs()));
        params.put("errorMessage", TypedValue.of(attempt.errorMessage()));

        return bindAll(db.sql(sql), params)
                .fetch()
                .rowsUpdated()
                .thenReturn(attempt);
    }

    @Override
    public Flux<DeliveryAttempt> findByEventId(String eventId) {
        String sql = """
                SELECT id, event_id, attempt_number, replay_count, attempted_at,
                       outcome, http_status, duration_ms, error_message
                FROM delivery_attempt
                WHERE event_id = :eventId
                ORDER BY attempted_at DESC, attempt_number DESC
                """;

        return db.sql(sql)
                .bind("eventId", eventId)
                .map(R2dbcDeliveryAttemptRepositoryAdapter::toDomainAttempt)
                .all();
    }

    private static DeliveryAttempt toDomainAttempt(Readable row) {
        return new DeliveryAttempt(
                row.get("id", UUID.class),
                row.get("event_id", String.class),
                row.get("attempt_number", Integer.class),
                row.get("replay_count", Integer.class),
                toDomain(row.get("attempted_at", OffsetDateTime.class)),
                AttemptOutcome.valueOf(row.get("outcome", String.class)),
                row.get("http_status", Integer.class),
                row.get("duration_ms", Long.class),
                row.get("error_message", String.class));
    }
}
