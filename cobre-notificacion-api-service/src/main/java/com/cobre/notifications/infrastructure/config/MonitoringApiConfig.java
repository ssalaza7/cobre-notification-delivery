package com.cobre.notifications.infrastructure.config;

import com.cobre.notifications.application.port.in.GetNotificationEventUseCase;
import com.cobre.notifications.application.port.in.IssueAccessTokenUseCase;
import com.cobre.notifications.application.port.in.ManageSubscriptionsUseCase;
import com.cobre.notifications.application.port.in.QueryNotificationEventsUseCase;
import com.cobre.notifications.application.port.in.ReplayNotificationEventUseCase;
import com.cobre.notifications.application.port.out.AccessTokenIssuerPort;
import com.cobre.notifications.application.port.out.DeliveryAttemptRepositoryPort;
import com.cobre.notifications.application.port.out.DeliveryQueuePort;
import com.cobre.notifications.application.port.out.MetricsPort;
import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.application.port.out.SecretGeneratorPort;
import com.cobre.notifications.application.port.out.SubscriptionRepositoryPort;
import com.cobre.notifications.application.port.out.WebhookUrlPolicyPort;
import com.cobre.notifications.application.service.GetNotificationEventService;
import com.cobre.notifications.application.service.IssueAccessTokenService;
import com.cobre.notifications.application.service.ManageSubscriptionsService;
import com.cobre.notifications.application.service.QueryNotificationEventsService;
import com.cobre.notifications.application.service.ReplayNotificationEventService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.cobre.notifications.infrastructure.security.OidcAccessTokenIssuer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;

/**
 * Cableado de la API.
 *
 * <p>Solo declara los casos de uso de lectura y el reenvio. No conoce el receptor de
 * Kafka ni el cliente de webhooks: ninguno de los dos esta en su classpath.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SecurityProperties.class)
public class MonitoringApiConfig {

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

    @Bean
    ManageSubscriptionsUseCase manageSubscriptionsUseCase(
            SubscriptionRepositoryPort subscriptions,
            WebhookUrlPolicyPort urlPolicy,
            SecretGeneratorPort secretGenerator) {
        return new ManageSubscriptionsService(subscriptions, urlPolicy, secretGenerator);
    }

    @Bean
    IssueAccessTokenUseCase issueAccessTokenUseCase(AccessTokenIssuerPort issuer, MetricsPort metrics) {
        return new IssueAccessTokenService(issuer, metrics);
    }

    /**
     * Cliente hacia el proveedor de identidad.
     *
     * <p>Propio y no el del worker: aquel esta afinado para webhooks de terceros lentos
     * y no sigue redirecciones, y el proveedor no tiene nada que ver con eso.
     */
    @Bean
    WebClient oidcWebClient() {
        return WebClient.builder().build();
    }

    @Bean
    AccessTokenIssuerPort accessTokenIssuerPort(WebClient oidcWebClient, SecurityProperties properties) {
        return new OidcAccessTokenIssuer(oidcWebClient, properties);
    }
}
