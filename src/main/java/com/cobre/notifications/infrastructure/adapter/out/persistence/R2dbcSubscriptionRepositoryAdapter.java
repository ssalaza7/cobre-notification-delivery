package com.cobre.notifications.infrastructure.adapter.out.persistence;

import com.cobre.notifications.application.port.out.SubscriptionRepositoryPort;
import com.cobre.notifications.domain.model.Subscription;
import com.cobre.notifications.infrastructure.config.WebhookProperties;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Resuelve la suscripcion activa de un cliente.
 *
 * <p>Prioriza la suscripcion especifica del tipo de evento sobre el comodin, de modo
 * que un cliente pueda enviar un tipo concreto a una URL distinta sin duplicar el
 * resto de la configuracion.
 */
@Repository
public class R2dbcSubscriptionRepositoryAdapter implements SubscriptionRepositoryPort {

    private final DatabaseClient db;
    private final WebhookProperties properties;

    public R2dbcSubscriptionRepositoryAdapter(DatabaseClient db, WebhookProperties properties) {
        this.db = db;
        this.properties = properties;
    }

    @Override
    public Mono<Subscription> findActiveFor(String clientId, String eventType) {
        String sql = """
                SELECT id, client_id, event_type, webhook_url, signing_secret, active
                FROM subscription
                WHERE client_id = :clientId
                  AND active
                  AND event_type IN (:eventType, '*')
                ORDER BY CASE WHEN event_type = '*' THEN 1 ELSE 0 END
                LIMIT 1
                """;

        return db.sql(sql)
                .bind("clientId", clientId)
                .bind("eventType", eventType)
                .map(R2dbcSubscriptionRepositoryAdapter::toDomainSubscription)
                .one()
                .map(this::applyDemoOverride);
    }

    /**
     * Sustituye la URL configurada cuando se define {@code cobre.webhook.override-url}.
     *
     * <p>Existe solo para la demostracion en vivo: la URL destino de la prueba se
     * entrega el mismo dia de la presentacion y esto evita tener que tocar la base de
     * datos delante del panel. Si la propiedad no esta definida, no hace nada.
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
