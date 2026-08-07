package com.cobre.notifications.infrastructure.adapter.out.persistence;

import com.cobre.notifications.application.port.out.SubscriptionRepositoryPort;
import com.cobre.notifications.domain.model.Subscription;
import com.cobre.notifications.infrastructure.config.WebhookProperties;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Registro de suscripciones.
 *
 * <p>Al resolver el destino prioriza la suscripcion especifica del tipo de evento sobre
 * el comodin, de modo que un cliente pueda enviar un tipo concreto a una URL distinta sin
 * duplicar el resto de la configuracion.
 */
@Repository
public class R2dbcSubscriptionRepositoryAdapter implements SubscriptionRepositoryPort {

    private static final String COLUMNAS =
            "id, client_id, event_type, webhook_url, signing_secret, active";

    private final DatabaseClient db;
    private final WebhookProperties properties;

    public R2dbcSubscriptionRepositoryAdapter(DatabaseClient db, WebhookProperties properties) {
        this.db = db;
        this.properties = properties;
    }

    @Override
    public Mono<Subscription> findActiveFor(String clientId, String eventType) {
        String sql = "SELECT " + COLUMNAS + " FROM subscription"
                + " WHERE client_id = :clientId AND active AND event_type IN (:eventType, '*')"
                + " ORDER BY CASE WHEN event_type = '*' THEN 1 ELSE 0 END"
                + " LIMIT 1";

        return db.sql(sql)
                .bind("clientId", clientId)
                .bind("eventType", eventType)
                .map(R2dbcSubscriptionRepositoryAdapter::toDomainSubscription)
                .one()
                .map(this::applyDemoOverride);
    }

    @Override
    public Flux<Subscription> findAllActiveByClientId(String clientId) {
        String sql = "SELECT " + COLUMNAS + " FROM subscription"
                + " WHERE client_id = :clientId AND active"
                + " ORDER BY (event_type = '*'), event_type";

        return db.sql(sql)
                .bind("clientId", clientId)
                .map(R2dbcSubscriptionRepositoryAdapter::toDomainSubscription)
                .all();
    }

    /**
     * Alta o reemplazo.
     *
     * <p>El conflicto se resuelve sobre el indice parcial de filas activas por
     * {@code (client_id, event_type)} y <b>conserva el secreto existente</b>: rotarlo en
     * cada cambio de URL romperia la verificacion de firma del cliente sin avisarle.
     */
    @Override
    public Mono<Subscription> save(Subscription subscription) {
        String sql = "INSERT INTO subscription (" + COLUMNAS + ")"
                + " VALUES (:id, :clientId, :eventType, :webhookUrl, :signingSecret, TRUE)"
                + " ON CONFLICT (client_id, event_type) WHERE active"
                + " DO UPDATE SET webhook_url = EXCLUDED.webhook_url"
                + " RETURNING " + COLUMNAS;

        return db.sql(sql)
                .bind("id", subscription.id())
                .bind("clientId", subscription.clientId())
                .bind("eventType", subscription.eventType())
                .bind("webhookUrl", subscription.webhookUrl())
                .bind("signingSecret", subscription.signingSecret())
                .map(R2dbcSubscriptionRepositoryAdapter::toDomainSubscription)
                .one();
    }

    /**
     * Desactiva en lugar de borrar: la bitacora de lo ya entregado apunta a esta fila y
     * eliminarla dejaria el historial huerfano.
     */
    @Override
    public Mono<Boolean> deactivate(String clientId, String eventType) {
        String sql = "UPDATE subscription SET active = FALSE"
                + " WHERE client_id = :clientId AND event_type = :eventType AND active";

        return db.sql(sql)
                .bind("clientId", clientId)
                .bind("eventType", eventType)
                .fetch()
                .rowsUpdated()
                .map(filas -> filas > 0);
    }

    /**
     * Sustituye la URL configurada cuando se define {@code cobre.webhook.override-url}.
     *
     * <p>Existe solo para la demostracion en vivo, donde la URL destino se entrega el
     * mismo dia. Si la propiedad no esta definida, no hace nada.
     */
    private Subscription applyDemoOverride(Subscription subscription) {
        String override = properties.overrideUrl();
        return override == null || override.isBlank()
                ? subscription
                : subscription.withWebhookUrl(override);
    }

    private static Subscription toDomainSubscription(Readable row) {
        return new Subscription(
                row.get("id", UUID.class),
                row.get("client_id", String.class),
                row.get("event_type", String.class),
                row.get("webhook_url", String.class),
                row.get("signing_secret", String.class),
                Boolean.TRUE.equals(row.get("active", Boolean.class)));
    }
}
