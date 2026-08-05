package com.cobre.notifications.infrastructure.adapter.out.messaging;

import com.cobre.notifications.infrastructure.config.MessagingProperties;
import com.cobre.notifications.infrastructure.config.RetryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Sender;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RabbitTopologyInitializerTest {

    private static final MessagingProperties MESSAGING = new MessagingProperties(
            "cobre.platform.events", "cobre.notifications.inbound", "#",
            "cobre.notifications", "cobre.notifications.delivery", "delivery",
            "cobre.notifications.retry", "cobre.notifications.dlq", "dead", 64);

    private static final RetryProperties RETRY = new RetryProperties(
            3, List.of(Duration.ofSeconds(5), Duration.ofSeconds(30)), 0.2);

    private final Sender sender = mock(Sender.class);

    @BeforeEach
    void setUp() {
        when(sender.declareExchange(any())).thenReturn(Mono.empty());
        when(sender.declareQueue(any())).thenReturn(Mono.empty());
        when(sender.bind(any())).thenReturn(Mono.empty());
    }

    private List<QueueSpecification> declaredQueues() {
        ArgumentCaptor<QueueSpecification> captor = ArgumentCaptor.forClass(QueueSpecification.class);
        verify(sender, org.mockito.Mockito.atLeastOnce()).declareQueue(captor.capture());
        return captor.getAllValues();
    }

    private void declare() {
        new RabbitTopologyInitializer(sender, MESSAGING, RETRY).run(null);
    }

    @Test
    @DisplayName("declara una cola de retardo por cada escalon de la politica de reintentos")
    void declara_una_cola_por_escalon() {
        declare();

        assertThat(declaredQueues())
                .extracting(QueueSpecification::getName)
                .contains("cobre.notifications.delivery.retry.5s", "cobre.notifications.delivery.retry.30s");
    }

    @Test
    @DisplayName("cada cola de retardo devuelve el mensaje a la cola de entrega al expirar")
    void las_colas_de_retardo_reencaminan_por_dead_letter() {
        declare();

        QueueSpecification retryQueue = declaredQueues().stream()
                .filter(queue -> "cobre.notifications.delivery.retry.5s".equals(queue.getName()))
                .findFirst()
                .orElseThrow();

        Map<String, Object> arguments = retryQueue.getArguments();
        assertThat(arguments).containsEntry("x-dead-letter-exchange", "cobre.notifications");
        assertThat(arguments).containsEntry("x-dead-letter-routing-key", "delivery");
        assertThat(retryQueue.isDurable()).isTrue();
    }

    @Test
    @DisplayName("la cola de entrega descarta hacia la cola muerta lo que se rechaza")
    void la_cola_de_entrega_apunta_a_la_dlq() {
        declare();

        QueueSpecification deliveryQueue = declaredQueues().stream()
                .filter(queue -> "cobre.notifications.delivery".equals(queue.getName()))
                .findFirst()
                .orElseThrow();

        assertThat(deliveryQueue.getArguments())
                .containsEntry("x-dead-letter-exchange", "cobre.notifications")
                .containsEntry("x-dead-letter-routing-key", "dead");
    }

    @Test
    @DisplayName("declara la cola propia enganchada al bus de eventos de la plataforma")
    void engancha_la_cola_de_entrada() {
        declare();

        assertThat(declaredQueues())
                .extracting(QueueSpecification::getName)
                .contains("cobre.notifications.inbound", "cobre.notifications.dlq");

        ArgumentCaptor<BindingSpecification> bindings = ArgumentCaptor.forClass(BindingSpecification.class);
        verify(sender, org.mockito.Mockito.atLeastOnce()).bind(bindings.capture());
        assertThat(bindings.getAllValues())
                .anyMatch(binding -> "cobre.platform.events".equals(binding.getExchange())
                        && "#".equals(binding.getRoutingKey()));
    }

    @Test
    @DisplayName("los reintentos se enrutan por su clave de escalon")
    void enruta_los_reintentos_por_escalon() {
        declare();

        ArgumentCaptor<BindingSpecification> bindings = ArgumentCaptor.forClass(BindingSpecification.class);
        verify(sender, org.mockito.Mockito.atLeastOnce()).bind(bindings.capture());

        assertThat(bindings.getAllValues())
                .extracting(BindingSpecification::getRoutingKey)
                .contains("retry.5s", "retry.30s", "delivery", "dead");
    }
}
