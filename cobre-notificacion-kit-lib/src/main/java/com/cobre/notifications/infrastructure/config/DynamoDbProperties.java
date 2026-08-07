package com.cobre.notifications.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion del almacen de notificaciones sobre DynamoDB.
 *
 * @param region        region de AWS
 * @param endpoint      direccion alterna del servicio. Se usa para apuntar a DynamoDB
 *                      Local; vacio en AWS, donde vale el endpoint real
 * @param tableName     tabla unica que guarda eventos e intentos
 * @param subscriptionsTableName tabla de suscripciones. Es tabla aparte y no una
 *                      particion mas de la anterior porque nunca se leen juntas: el
 *                      worker resuelve el destino en una consulta y escribe el estado
 *                      en otra. Ademas tienen perfiles distintos, los eventos crecen
 *                      con el trafico y caducan, las suscripciones crecen con el
 *                      numero de clientes y no caducan
 * @param clientIndex   indice secundario por cliente y fecha, que sostiene el listado
 * @param attemptTtl    cuanto vive un intento de entrega antes de que DynamoDB lo
 *                      expire. La bitacora responde quejas recientes; pasado el plazo
 *                      el evento conserva su estado final, que es lo que no caduca
 * @param counterShards particiones del contador de backlog. Mas de una porque todas
 *                      las transiciones escriben ahi: un solo item concentraria cada
 *                      escritura del sistema en una particion y la estrangularia
 * @param createTable   crear la tabla al arrancar si no existe. Solo para el entorno
 *                      local; en AWS la infraestructura no la declara la aplicacion
 */
@ConfigurationProperties(prefix = "cobre.dynamodb")
public record DynamoDbProperties(
        String region,
        String endpoint,
        String tableName,
        String subscriptionsTableName,
        String clientIndex,
        Duration attemptTtl,
        int counterShards,
        boolean createTable) {

    public DynamoDbProperties {
        region = region != null && !region.isBlank() ? region : "us-east-1";
        tableName = tableName != null && !tableName.isBlank() ? tableName : "notifications";
        subscriptionsTableName = subscriptionsTableName != null && !subscriptionsTableName.isBlank()
                ? subscriptionsTableName
                : "subscriptions";
        clientIndex = clientIndex != null && !clientIndex.isBlank() ? clientIndex : "gsi_client_created";
        attemptTtl = attemptTtl != null ? attemptTtl : Duration.ofDays(90);
        counterShards = counterShards > 0 ? counterShards : 10;
    }
}
