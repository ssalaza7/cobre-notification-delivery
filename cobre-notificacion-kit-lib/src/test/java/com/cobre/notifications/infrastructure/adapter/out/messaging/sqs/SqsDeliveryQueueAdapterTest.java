package com.cobre.notifications.infrastructure.adapter.out.messaging.sqs;

import com.cobre.notifications.infrastructure.config.SqsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifica el contrato de la cola de trabajo.
 *
 * <p>Lo mas caracteristico de SQS es que el retardo es nativo: no hacen falta colas de
 * espera ni reglas de devolucion, basta un atributo del mensaje.
 */
class SqsDeliveryQueueAdapterTest {

    private static final String COLA = "http://localhost:9324/000000000000/notifications-delivery";
    private static final String DLQ = "http://localhost:9324/000000000000/notifications-dlq";

    private final SqsAsyncClient sqs = mock(SqsAsyncClient.class);
    private SqsDeliveryQueueAdapter adapter;

    private static SqsProperties properties() {
        return new SqsProperties("us-east-1", "http://localhost:9324", COLA, DLQ,
                10, Duration.ofSeconds(20), Duration.ofSeconds(60));
    }

    @BeforeEach
    void setUp() {
        adapter = new SqsDeliveryQueueAdapter(sqs, properties(), JsonMapper.builder().build());
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()));
    }

    private SendMessageRequest capturar() {
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("encola una entrega inmediata en la cola de trabajo")
    void encola_sin_retardo() {
        StepVerifier.create(adapter.enqueue("EVT001", "CLIENT002")).verifyComplete();

        SendMessageRequest peticion = capturar();
        assertThat(peticion.queueUrl()).isEqualTo(COLA);
        assertThat(peticion.delaySeconds()).isZero();
        assertThat(peticion.messageBody())
                .contains("\"event_id\":\"EVT001\"")
                .contains("\"client_id\":\"CLIENT002\"");
    }

    @Test
    @DisplayName("el reintento viaja con DelaySeconds: no hacen falta colas de espera")
    void el_retardo_es_nativo() {
        StepVerifier.create(adapter.enqueueRetry("EVT001", "CLIENT002", Duration.ofSeconds(30)))
                .verifyComplete();

        SendMessageRequest peticion = capturar();
        assertThat(peticion.queueUrl()).isEqualTo(COLA);
        assertThat(peticion.delaySeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("una espera mayor al tope de SQS se recorta en vez de fallar")
    void recorta_al_tope_de_sqs() {
        StepVerifier.create(adapter.enqueueRetry("EVT001", "CLIENT002", Duration.ofMinutes(45)))
                .verifyComplete();

        // Quedarse corto en el backoff es molesto; perder el reintento por una
        // excepcion seria perder la notificacion.
        assertThat(capturar().delaySeconds()).isEqualTo((int) SqsProperties.MAX_DELAY.toSeconds());
    }

    @Test
    @DisplayName("un fallo de SQS se propaga: el consumidor no confirmara su mensaje")
    void propaga_el_fallo() {
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("SQS no disponible")));

        StepVerifier.create(adapter.enqueue("EVT001", "CLIENT002"))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    @DisplayName("el tope de mensajes por lote no puede pasar de diez, que es el limite de SQS")
    void acota_el_lote() {
        SqsProperties excedida = new SqsProperties("us-east-1", null, COLA, DLQ,
                50, Duration.ofSeconds(20), Duration.ofSeconds(60));

        assertThat(excedida.maxMessages()).isEqualTo(10);
    }
}
