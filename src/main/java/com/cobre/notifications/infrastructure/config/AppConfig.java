package com.cobre.notifications.infrastructure.config;

import com.cobre.notifications.application.port.in.DeliverNotificationEventUseCase;
import com.cobre.notifications.application.port.in.GetNotificationEventUseCase;
import com.cobre.notifications.application.port.in.IngestNotificationEventUseCase;
import com.cobre.notifications.application.port.in.IssueAccessTokenUseCase;
import com.cobre.notifications.application.port.in.QueryNotificationEventsUseCase;
import com.cobre.notifications.application.port.in.ReplayNotificationEventUseCase;
import com.cobre.notifications.application.port.out.AccessTokenIssuerPort;
import com.cobre.notifications.application.port.out.ApiCredentialRepositoryPort;
import com.cobre.notifications.application.port.out.DeliveryAttemptRepositoryPort;
import com.cobre.notifications.application.port.out.DeliveryQueuePort;
import com.cobre.notifications.application.port.out.MetricsPort;
import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.application.port.out.SecretHasherPort;
import com.cobre.notifications.application.port.out.SubscriptionRepositoryPort;
import com.cobre.notifications.application.port.out.WebhookClientPort;
import com.cobre.notifications.application.service.DeliverNotificationEventService;
import com.cobre.notifications.application.service.GetNotificationEventService;
import com.cobre.notifications.application.service.IngestNotificationEventService;
import com.cobre.notifications.application.service.IssueAccessTokenService;
import com.cobre.notifications.application.service.QueryNotificationEventsService;
import com.cobre.notifications.application.service.ReplayNotificationEventService;
import com.cobre.notifications.domain.model.RetryPolicy;
import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Cableado de la aplicacion.
 *
 * <p>Los casos de uso se declaran aqui como beans en vez de anotarlos con
 * {@code @Service}. Es lo que mantiene la regla de dependencias de la arquitectura
 * hexagonal: los paquetes {@code domain} y {@code application} no importan una sola
 * clase de Spring, asi que se pueden compilar y probar sin el framework. Toda la
 * dependencia hacia Spring queda confinada a {@code infrastructure}.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({
        WebhookProperties.class,
        RetryProperties.class,
        KafkaProperties.class,
        SqsProperties.class,
        SecurityProperties.class
})
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Fuente de aleatoriedad para el jitter. Delega en {@code ThreadLocalRandom} en
     * cada llamada —y no una sola vez al crear el bean— porque una instancia de
     * {@code ThreadLocalRandom} capturada y compartida entre hilos pierde justamente
     * la propiedad que la hace util.
     */
    @Bean
    RandomGenerator randomGenerator() {
        return () -> ThreadLocalRandom.current().nextLong();
    }

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
    IngestNotificationEventUseCase ingestNotificationEventUseCase(
            NotificationEventRepositoryPort events,
            DeliveryQueuePort deliveryQueue,
            MetricsPort metrics,
            Clock clock) {
        return new IngestNotificationEventService(events, deliveryQueue, metrics, clock);
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
    IssueAccessTokenUseCase issueAccessTokenUseCase(
            ApiCredentialRepositoryPort credentials,
            SecretHasherPort hasher,
            AccessTokenIssuerPort issuer,
            MetricsPort metrics) {
        return new IssueAccessTokenService(credentials, hasher, issuer, metrics);
    }

    @Bean
    QueryNotificationEventsUseCase queryNotificationEventsUseCase(NotificationEventRepositoryPort events) {
        return new QueryNotificationEventsService(events);
    }

    @Bean
    GetNotificationEventUseCase getNotificationEventUseCase(
            NotificationEventRepositoryPort events, DeliveryAttemptRepositoryPort attempts) {
        return new GetNotificationEventService(events, attempts);
    }

    @Bean
    ReplayNotificationEventUseCase replayNotificationEventUseCase(
            NotificationEventRepositoryPort events,
            DeliveryQueuePort deliveryQueue,
            MetricsPort metrics,
            Clock clock) {
        return new ReplayNotificationEventService(events, deliveryQueue, metrics, clock);
    }
}
