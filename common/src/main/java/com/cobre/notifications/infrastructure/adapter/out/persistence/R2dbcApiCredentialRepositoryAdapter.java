package com.cobre.notifications.infrastructure.adapter.out.persistence;

import com.cobre.notifications.application.port.out.ApiCredentialRepositoryPort;
import com.cobre.notifications.domain.model.ApiCredential;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

/** Registro de credenciales de API sobre R2DBC. */
@Repository
public class R2dbcApiCredentialRepositoryAdapter implements ApiCredentialRepositoryPort {

    private final DatabaseClient db;

    public R2dbcApiCredentialRepositoryAdapter(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<ApiCredential> findActiveByClientId(String clientId) {
        return db.sql("""
                        SELECT client_id, secret_hash, scopes, active
                        FROM api_credential
                        WHERE client_id = :clientId AND active
                        """)
                .bind("clientId", clientId)
                .map(R2dbcApiCredentialRepositoryAdapter::toDomain)
                .one();
    }

    private static ApiCredential toDomain(Readable row) {
        String scopes = row.get("scopes", String.class);
        List<String> parsed = scopes == null || scopes.isBlank()
                ? List.of()
                : Arrays.stream(scopes.trim().split("\\s+")).toList();

        return new ApiCredential(
                row.get("client_id", String.class),
                row.get("secret_hash", String.class),
                parsed,
                Boolean.TRUE.equals(row.get("active", Boolean.class)));
    }
}
