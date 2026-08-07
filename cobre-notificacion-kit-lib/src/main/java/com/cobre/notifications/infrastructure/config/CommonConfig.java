package com.cobre.notifications.infrastructure.config;

import com.cobre.notifications.application.port.out.DeliveryQueuePort;
import com.cobre.notifications.infrastructure.adapter.out.messaging.sqs.SqsDeliveryQueueAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Beans que necesitan los tres ejecutables.
 *
 * <p>Vive en la libreria compartida porque los tres modulos publican en la cola de
 * entrega: el consumidor encola lo que ingesta, el worker reencola los reintentos y la
 * API encola los reenvios manuales. Lo que no comparten —el receptor de Kafka, el
 * cliente de webhooks, la cadena de seguridad— se declara en cada modulo.
 */
@Configuration
@EnableConfigurationProperties({SqsProperties.class, WebhookProperties.class, DynamoDbProperties.class})
public class CommonConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Fuente de aleatoriedad para el jitter. Delega en {@code ThreadLocalRandom} en cada
     * llamada —y no una sola vez al crear el bean— porque una instancia capturada y
     * compartida entre hilos pierde justamente la propiedad que la hace util.
     */
    @Bean
    RandomGenerator randomGenerator() {
        return () -> ThreadLocalRandom.current().nextLong();
    }

    /**
     * Cliente de SQS.
     *
     * <p>Con endpoint configurado se usan credenciales fijas, que es lo que pide un
     * emulador local. Sin el, se toman las credenciales del entorno —rol de la tarea en
     * ECS, perfil, variables—, que es lo correcto en AWS: la aplicacion nunca lleva
     * llaves escritas.
     */
    @Bean(destroyMethod = "close")
    SqsAsyncClient sqsAsyncClient(SqsProperties properties) {
        var builder = SqsAsyncClient.builder().region(Region.of(properties.region()));

        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("local", "local")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    @Bean
    DeliveryQueuePort deliveryQueuePort(
            SqsAsyncClient sqs, SqsProperties properties, ObjectMapper objectMapper) {
        return new SqsDeliveryQueueAdapter(sqs, properties, objectMapper);
    }

    /**
     * Cliente de DynamoDB. Mismo criterio de credenciales que el de SQS: fijas cuando
     * hay endpoint —lo que pide un emulador local— y tomadas del entorno en AWS.
     */
    @Bean(destroyMethod = "close")
    DynamoDbAsyncClient dynamoDbAsyncClient(DynamoDbProperties properties) {
        var builder = DynamoDbAsyncClient.builder().region(Region.of(properties.region()));

        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("local", "local")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }
}
