package com.cobre.notifications.infrastructure.adapter.out.persistence.dynamodb;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.cobre.notifications.infrastructure.config.DynamoDbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

/**
 * Crea la tabla, su indice y el TTL al arrancar, si no existen.
 *
 * <p>Solo para el entorno local, y por eso viene apagado por defecto: en AWS la tabla la
 * declara la infraestructura como codigo, no la aplicacion. Una aplicacion con permiso
 * para crear tablas necesita ese permiso en produccion, donde no lo va a usar nunca.
 *
 * <p>Ocupa el lugar que tenia Flyway para el esquema de eventos. Flyway sigue vivo para
 * las tablas relacionales que se quedan: suscripciones y credenciales.
 */
@Component
public class DynamoDbTableInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbTableInitializer.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final DynamoDbAsyncClient dynamo;
    private final DynamoDbProperties properties;

    public DynamoDbTableInitializer(DynamoDbAsyncClient dynamo, DynamoDbProperties properties) {
        this.dynamo = dynamo;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!properties.createTable()) {
            return;
        }
        String table = properties.tableName();
        try {
            createTable(table);
            log.info("Tabla {} creada", table);
        } catch (Exception e) {
            // La tabla ya existia: es lo normal en cualquier arranque que no sea el
            // primero, y no hay nada que hacer.
            if (!(rootCause(e) instanceof ResourceInUseException)) {
                throw e;
            }
            log.debug("La tabla {} ya existia", table);
            return;
        }
        enableTtl(table);
    }

    private void createTable(String table) throws Exception {
        dynamo.createTable(CreateTableRequest.builder()
                        .tableName(table)
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .attributeDefinitions(
                                attribute(NotificationTable.PK),
                                attribute(NotificationTable.SK),
                                attribute(NotificationTable.GSI_PK),
                                attribute(NotificationTable.GSI_SK))
                        .keySchema(
                                key(NotificationTable.PK, KeyType.HASH),
                                key(NotificationTable.SK, KeyType.RANGE))
                        .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                                .indexName(properties.clientIndex())
                                .keySchema(
                                        key(NotificationTable.GSI_PK, KeyType.HASH),
                                        key(NotificationTable.GSI_SK, KeyType.RANGE))
                                // El listado devuelve el evento completo. Con una
                                // proyeccion parcial habria que releer cada item de la
                                // tabla base, que es justo lo que el indice evita.
                                .projection(Projection.builder()
                                        .projectionType(ProjectionType.ALL)
                                        .build())
                                .build())
                        .build())
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        dynamo.waiter().waitUntilTableExists(request -> request.tableName(table))
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    /** Marca el atributo que DynamoDB vigila para expirar los intentos. */
    private void enableTtl(String table) throws Exception {
        dynamo.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                        .tableName(table)
                        .timeToLiveSpecification(TimeToLiveSpecification.builder()
                                .attributeName(NotificationTable.TTL)
                                .enabled(true)
                                .build())
                        .build())
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        log.info("TTL activado sobre {} en la tabla {}", NotificationTable.TTL, table);
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder()
                .attributeName(name)
                .attributeType(ScalarAttributeType.S)
                .build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
