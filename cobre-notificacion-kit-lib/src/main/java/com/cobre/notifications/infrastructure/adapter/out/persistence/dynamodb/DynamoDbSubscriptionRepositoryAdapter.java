package com.cobre.notifications.infrastructure.adapter.out.persistence.dynamodb;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.cobre.notifications.application.port.out.SubscriptionRepositoryPort;
import com.cobre.notifications.domain.model.Subscription;
import com.cobre.notifications.infrastructure.config.DynamoDbProperties;
import com.cobre.notifications.infrastructure.config.WebhookProperties;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;

/**
 * Registro de suscripciones sobre DynamoDB.
 *
 * <p>Una particion por cliente. Todas sus suscripciones se leen en una consulta, y como
 * son pocas —una por tipo de evento, mas el comodin— resolver cual aplica en memoria
 * cuesta menos que dos lecturas por clave.
 *
 * <p>Al resolver el destino prioriza la suscripcion especifica del tipo de evento sobre
 * el comodin, de modo que un cliente pueda enviar un tipo concreto a una URL distinta sin
 * duplicar el resto de la configuracion.
 */
@Repository
@DependsOn("dynamoDbTableInitializer")
public class DynamoDbSubscriptionRepositoryAdapter implements SubscriptionRepositoryPort {

    private final DynamoDbAsyncClient dynamo;
    private final DynamoDbProperties properties;
    private final WebhookProperties webhookProperties;

    public DynamoDbSubscriptionRepositoryAdapter(
            DynamoDbAsyncClient dynamo,
            DynamoDbProperties properties,
            WebhookProperties webhookProperties) {
        this.dynamo = dynamo;
        this.properties = properties;
        this.webhookProperties = webhookProperties;
    }

    @Override
    public Mono<Subscription> findActiveFor(String clientId, String eventType) {
        return activas(clientId)
                .filter(subscription -> subscription.covers(eventType))
                // Especifica antes que comodin: el orden alfabetico de la clave no
                // sirve, porque '*' no tiene por que quedar al final.
                .sort(Comparator.comparing(subscription ->
                        Subscription.ALL_EVENT_TYPES.equals(subscription.eventType()) ? 1 : 0))
                .next()
                .map(this::applyDemoOverride);
    }

    @Override
    public Flux<Subscription> findAllActiveByClientId(String clientId) {
        return activas(clientId)
                .sort(Comparator
                        .comparing((Subscription s) ->
                                Subscription.ALL_EVENT_TYPES.equals(s.eventType()) ? 1 : 0)
                        .thenComparing(Subscription::eventType));
    }

    /**
     * Alta o reemplazo.
     *
     * <p>Escribe la URL y <b>conserva el secreto que ya hubiera</b> con
     * {@code if_not_exists}: rotarlo en cada cambio de URL romperia la verificacion de
     * firma del cliente sin avisarle. El caso de uso ya lo preserva, pero dos altas
     * simultaneas podrian leer ambas en vacio y generar dos secretos distintos; la
     * condicion cierra esa carrera en el motor.
     */
    @Override
    public Mono<Subscription> save(Subscription subscription) {
        Map<String, AttributeValue> valores = Map.of(
                ":id", NotificationTable.s(subscription.id().toString()),
                ":clientId", NotificationTable.s(subscription.clientId()),
                ":eventType", NotificationTable.s(subscription.eventType()),
                ":webhookUrl", NotificationTable.s(subscription.webhookUrl()),
                ":signingSecret", NotificationTable.s(subscription.signingSecret()),
                ":active", AttributeValue.fromBool(true));

        Map<String, String> nombres = Map.of(
                "#id", SubscriptionTable.ID,
                "#clientId", SubscriptionTable.CLIENT_ID,
                "#eventType", SubscriptionTable.EVENT_TYPE,
                "#webhookUrl", SubscriptionTable.WEBHOOK_URL,
                "#signingSecret", SubscriptionTable.SIGNING_SECRET,
                "#active", SubscriptionTable.ACTIVE);

        return Mono.fromFuture(() -> dynamo.updateItem(request -> request
                        .tableName(properties.subscriptionsTableName())
                        .key(clave(subscription.clientId(), subscription.eventType()))
                        .updateExpression("""
                                SET #webhookUrl = :webhookUrl,
                                    #active = :active,
                                    #id = if_not_exists(#id, :id),
                                    #clientId = :clientId,
                                    #eventType = :eventType,
                                    #signingSecret = if_not_exists(#signingSecret, :signingSecret)
                                """)
                        .expressionAttributeNames(nombres)
                        .expressionAttributeValues(valores)
                        .returnValues(ReturnValue.ALL_NEW)))
                .map(response -> SubscriptionTable.toDomain(response.attributes()));
    }

    /** Suscripciones activas del cliente. Una consulta sobre su particion. */
    private Flux<Subscription> activas(String clientId) {
        return Mono.fromFuture(() -> dynamo.query(request -> request
                        .tableName(properties.subscriptionsTableName())
                        .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                        .expressionAttributeNames(Map.of(
                                "#pk", SubscriptionTable.PK,
                                "#sk", SubscriptionTable.SK))
                        .expressionAttributeValues(Map.of(
                                ":pk", NotificationTable.s(SubscriptionTable.clientPk(clientId)),
                                ":prefix", NotificationTable.s(SubscriptionTable.prefix())))
                        // El worker resuelve el destino justo despues de que la API
                        // pudiera haberlo cambiado; una replica atrasada entregaria a la
                        // URL anterior.
                        .consistentRead(true)))
                .flatMapIterable(response -> {
                    List<Subscription> encontradas = response.items().stream()
                            .map(SubscriptionTable::toDomain)
                            .filter(Subscription::active)
                            .toList();
                    return encontradas;
                });
    }

    private Map<String, AttributeValue> clave(String clientId, String eventType) {
        return Map.of(
                SubscriptionTable.PK, NotificationTable.s(SubscriptionTable.clientPk(clientId)),
                SubscriptionTable.SK, NotificationTable.s(SubscriptionTable.subSk(eventType)));
    }

    /**
     * Sustituye la URL configurada cuando se define {@code cobre.webhook.override-url}.
     *
     * <p>Existe solo para la demostracion en vivo, donde la URL destino se entrega el
     * mismo dia. Si la propiedad no esta definida, no hace nada.
     */
    private Subscription applyDemoOverride(Subscription subscription) {
        String override = webhookProperties.overrideUrl();
        return override == null || override.isBlank()
                ? subscription
                : subscription.withWebhookUrl(override);
    }
}
