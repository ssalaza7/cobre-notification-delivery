package com.cobre.notifications.infrastructure.config;

import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase;
import com.cobre.notifications.application.port.out.DeliveryQueuePort;
import com.cobre.notifications.application.port.out.MetricsPort;
import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.application.service.IngestNotificationEventService;
import com.cobre.notifications.infrastructure.adapter.in.messaging.kafka.KafkaPlatformEventListener;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cableado del consumidor.
 *
 * <p>Kafka es el bus de la plataforma: retencion larga, varios lectores del mismo evento
 * y reprocesamiento historico. La entrega no se hace sobre el —el paralelismo lo topan
 * las particiones y un webhook lento bloquearia a todos los clientes que compartan la
 * suya— sino sobre la cola de trabajo, que es lo que este modulo alimenta.
 */
@Configuration
@EnableConfigurationProperties(KafkaProperties.class)
public class EventConsumerConfig {

    @Bean
    KafkaReceiver<String, String> kafkaReceiver(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.groupId());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Un consumidor nuevo empieza por el principio del topic: en un arranque en frio
        // se prefiere reprocesar a perder eventos.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // El offset se confirma explicitamente despues de procesar.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        return KafkaReceiver.create(
                ReceiverOptions.<String, String>create(config)
                        .subscription(List.of(properties.topic())));
    }

    @Bean
    IngestNotificationEventUseCase ingestNotificationEventUseCase(
            NotificationEventRepositoryPort events,
            DeliveryQueuePort deliveryQueue,
            MetricsPort metrics,
            Clock clock) {
        return new IngestNotificationEventService(events, deliveryQueue, metrics, clock);
    }

    @Bean
    KafkaPlatformEventListener kafkaPlatformEventListener(
            KafkaReceiver<String, String> receiver,
            IngestNotificationEventUseCase ingestUseCase,
            ObjectMapper objectMapper,
            KafkaProperties properties) {
        return new KafkaPlatformEventListener(receiver, ingestUseCase, objectMapper, properties);
    }
}
