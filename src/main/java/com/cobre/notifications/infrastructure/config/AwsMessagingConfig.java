package com.cobre.notifications.infrastructure.config;

import com.cobre.notifications.application.port.in.DeliverNotificationEventUseCase;
import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase;
import com.cobre.notifications.application.port.out.DeliveryQueuePort;
import com.cobre.notifications.infrastructure.adapter.in.messaging.kafka.KafkaPlatformEventListener;
import com.cobre.notifications.infrastructure.adapter.in.messaging.sqs.SqsDeliveryCommandListener;
import com.cobre.notifications.infrastructure.adapter.out.messaging.sqs.SqsDeliveryQueueAdapter;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Arquitectura objetivo: Kafka como bus de eventos y SQS como cola de trabajo.
 *
 * <p>Es el reparto correcto de responsabilidades, y la razon de que sean dos
 * tecnologias y no una: Kafka es un excelente bus —retencion larga, muchos lectores del
 * mismo evento, reprocesamiento— y una mala cola de trabajo. No tiene retardo por
 * mensaje, asi que el backoff exigiria un topic por escalon y codigo propio para
 * moverlos; y ordena por particion, de modo que un webhook lento bloquearia a todos los
 * clientes que compartan esa particion.
 *
 * <p>SQS es lo contrario: no promete orden —y esa "carencia" es justo lo que impide que
 * un destino caido afecte a los demas— y trae de fabrica el retardo por mensaje y la
 * cola muerta.
 *
 * <p>En local se apunta a Redpanda y ElasticMQ, que hablan los mismos protocolos. El
 * codigo es identico al que correria contra Confluent Cloud y SQS.
 */
@Configuration
public class AwsMessagingConfig {

    @Bean
    KafkaReceiver<String, String> kafkaReceiver(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.groupId());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Un consumidor nuevo empieza por el principio del topic, no por el final: en un
        // arranque en frio se prefiere reprocesar a perder eventos.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // El offset se confirma explicitamente despues de procesar.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        return KafkaReceiver.create(
                ReceiverOptions.<String, String>create(config)
                        .subscription(List.of(properties.topic())));
    }

    /**
     * Cliente de SQS.
     *
     * <p>Si hay endpoint configurado se usa con credenciales fijas, que es lo que pide
     * un emulador local. Sin el, se toman las credenciales del entorno —rol de la tarea
     * en ECS, perfil, variables— que es lo correcto en AWS: la aplicacion nunca lleva
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

    @Bean
    @ConditionalOnRole(ConditionalOnRole.WORKER)
    KafkaPlatformEventListener kafkaPlatformEventListener(
            KafkaReceiver<String, String> receiver,
            IngestNotificationEventUseCase ingestUseCase,
            ObjectMapper objectMapper,
            KafkaProperties properties) {
        return new KafkaPlatformEventListener(receiver, ingestUseCase, objectMapper, properties);
    }

    @Bean
    @ConditionalOnRole(ConditionalOnRole.WORKER)
    SqsDeliveryCommandListener sqsDeliveryCommandListener(
            SqsAsyncClient sqs,
            SqsProperties properties,
            DeliverNotificationEventUseCase deliverUseCase,
            ObjectMapper objectMapper) {
        return new SqsDeliveryCommandListener(sqs, properties, deliverUseCase, objectMapper);
    }
}
