package com.cobre.notifications.infrastructure.config;

import com.cobre.notifications.application.port.in.DeliverNotificationEventUseCase;
import com.cobre.notifications.application.port.out.DeliveryAttemptRepositoryPort;
import com.cobre.notifications.application.port.out.DeliveryQueuePort;
import com.cobre.notifications.application.port.out.MetricsPort;
import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.application.port.out.SubscriptionRepositoryPort;
import com.cobre.notifications.application.port.out.WebhookClientPort;
import com.cobre.notifications.application.service.DeliverNotificationEventService;
import com.cobre.notifications.domain.model.RetryPolicy;
import com.cobre.notifications.infrastructure.adapter.in.messaging.sqs.SqsDeliveryCommandListener;
import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.random.RandomGenerator;

/**
 * Cableado del worker.
 *
 * <p>Es el modulo que justifica el modelo reactivo: cada entrega espera la respuesta de
 * un tercero que puede tardar segundos o no contestar. Con un hilo por entrega, mil
 * destinos lentos serian mil hilos bloqueados.
 */
@Configuration
@EnableConfigurationProperties({WebhookProperties.class, RetryProperties.class})
public class DeliveryWorkerConfig {

    @Bean
    RetryPolicy retryPolicy(RetryProperties properties) {
        return properties.toPolicy();
    }

    @Bean
    WebClient webhookWebClient(WebhookProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis())
                .responseTimeout(properties.responseTimeout())
                // No seguir redirecciones: un 302 del destino podria reapuntar la
                // peticion a la red interna y evadir la validacion anti-SSRF.
                .followRedirect(false);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                // Cota de memoria por respuesta: el cuerpo lo controla un tercero.
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(64 * 1024))
                .build();
    }

    @Bean
    DeliverNotificationEventUseCase deliverNotificationEventUseCase(
            NotificationEventRepositoryPort events,
            DeliveryAttemptRepositoryPort attempts,
            SubscriptionRepositoryPort subscriptions,
            WebhookClientPort webhookClient,
            DeliveryQueuePort deliveryQueue,
            MetricsPort metrics,
            RetryPolicy retryPolicy,
            Clock clock,
            RandomGenerator random) {
        return new DeliverNotificationEventService(
                events, attempts, subscriptions, webhookClient, deliveryQueue,
                metrics, retryPolicy, clock, random);
    }

    @Bean
    SqsDeliveryCommandListener sqsDeliveryCommandListener(
            SqsAsyncClient sqs,
            SqsProperties properties,
            DeliverNotificationEventUseCase deliverUseCase,
            ObjectMapper objectMapper) {
        return new SqsDeliveryCommandListener(sqs, properties, deliverUseCase, objectMapper);
    }
}
