package com.cobre.notifications.infrastructure.adapter.out.persistence.dynamodb;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.cobre.notifications.application.port.out.SigningSecretStorePort;
import com.cobre.notifications.application.port.out.SubscriptionRepositoryPort;
import com.cobre.notifications.domain.model.Subscription;
import com.cobre.notifications.infrastructure.config.DynamoDbProperties;
import com.cobre.notifications.infrastructure.config.WebhookProperties;
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
public class DynamoDbSubscriptionRepositoryAdapter implements SubscriptionRepositoryPort {

    private final DynamoDbAsyncClient dynamo;
    private final DynamoDbProperties properties;
    private final WebhookProperties webhookProperties;
    private final SigningSecretStorePort secretos;

    public DynamoDbSubscriptionRepositoryAdapter(
            DynamoDbAsyncClient dynamo,
            DynamoDbProperties properties,
            WebhookProperties webhookProperties,
            SigningSecretStorePort secretos) {
        this.dynamo = dynamo;
        this.properties = properties;
        this.webhookProperties = webhookProperties;
        this.secretos = secretos;
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
        return secretos.store(subscription.clientId(), subscription.eventType(), subscription.signingSecret())
                .flatMap(referencia -> escribir(subscription, referencia));
    }

    private Mono<Subscription> escribir(Subscription subscription, String referencia) {
        Map<String, AttributeValue> valores = Map.of(
                ":id", NotificationTable.s(subscription.id().toString()),
                ":clientId", NotificationTable.s(subscription.clientId()),
                ":eventType", NotificationTable.s(subscription.eventType()),
                ":webhookUrl", NotificationTable.s(subscription.webhookUrl()),
                ":secretRef", NotificationTable.s(referencia),
                ":active", AttributeValue.fromBool(true));

        Map<String, String> nombres = Map.of(
                "#id", SubscriptionTable.ID,
                "#clientId", SubscriptionTable.CLIENT_ID,
                "#eventType", SubscriptionTable.EVENT_TYPE,
                "#webhookUrl", SubscriptionTable.WEBHOOK_URL,
                "#secretRef", SubscriptionTable.SECRET_REF,
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
                                    #secretRef = if_not_exists(#secretRef, :secretRef)
                                """)
                        .expressionAttributeNames(nombres)
                        .expressionAttributeValues(valores)
                        .returnValues(ReturnValue.ALL_NEW)))
                .map(response -> SubscriptionTable.toDomain(response.attributes(), subscription.signingSecret()));
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
                .flatMapIterable(response -> response.items())
                // El secreto no esta en la tabla: se resuelve contra el almacen. Una
                // referencia rota deja la suscripcion fuera, y el evento acaba
                // descartado en vez de entregarse sin firmar.
                .concatMap(item -> secretos.read(SubscriptionTable.secretRef(item))
                        .map(secreto -> SubscriptionTable.toDomain(item, secreto)))
                .filter(Subscription::active);
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
