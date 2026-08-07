package com.cobre.notifications.infrastructure.adapter.in.messaging.sqs;

import com.cobre.notifications.application.port.in.DeliverNotificationEventUseCase;
import com.cobre.notifications.domain.exception.NotificationEventNotFoundException;
import com.cobre.notifications.domain.model.DeliveryOutcome;
import com.cobre.notifications.infrastructure.config.SqsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * En SQS confirmar un mensaje significa <b>borrarlo</b>.
 *
 * <p>Mientras no se borre, reaparece al vencer el visibility timeout: si el proceso
 * muere a mitad del procesamiento, el mensaje vuelve y la notificacion no se pierde.
 */
class SqsDeliveryCommandListenerTest {

    private static final String COLA = "http://localhost:9324/000000000000/notifications-delivery";

    private final SqsAsyncClient sqs = mock(SqsAsyncClient.class);
    private final DeliverNotificationEventUseCase deliverUseCase = mock(DeliverNotificationEventUseCase.class);
    private SqsDeliveryCommandListener listener;

    private static Message mensaje(String cuerpo) {
        return Message.builder().body(cuerpo).receiptHandle("recibo-1").build();
    }

    @BeforeEach
    void setUp() {
        SqsProperties properties = new SqsProperties("us-east-1", "http://localhost:9324",
                COLA, COLA + "-dlq", 10, Duration.ofSeconds(20), Duration.ofSeconds(60));
        listener = new SqsDeliveryCommandListener(sqs, properties, deliverUseCase, JsonMapper.builder().build());
        when(sqs.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));
    }

    @Test
    @DisplayName("ejecuta la entrega con el identificador del mensaje")
    void ejecuta_la_entrega() {
        when(deliverUseCase.deliver("EVT001")).thenReturn(Mono.just(DeliveryOutcome.DELIVERED));

        StepVerifier.create(listener.handle("{\"event_id\":\"EVT001\",\"client_id\":\"CLIENT002\"}"))
                .verifyComplete();

        verify(deliverUseCase).deliver("EVT001");
    }

    @Test
    @DisplayName("un evento inexistente se descarta sin ensuciar la cola muerta")
    void descarta_ordenes_huerfanas() {
        when(deliverUseCase.deliver("EVT404"))
                .thenReturn(Mono.error(new NotificationEventNotFoundException("EVT404")));

        StepVerifier.create(listener.handle("{\"event_id\":\"EVT404\",\"client_id\":\"CLIENT002\"}"))
                .verifyComplete();
    }

    @Test
    @DisplayName("un mensaje corrupto falla, no se borra, y SQS lo llevara a la cola muerta")
    void un_mensaje_corrupto_falla() {
        StepVerifier.create(listener.handle("{esto no es json"))
                .expectError()
                .verify();

        verify(deliverUseCase, never()).deliver(anyString());
    }

    @Test
    @DisplayName("el mensaje se borra solo despues de procesarlo: borrar es confirmar")
    void borra_despues_de_procesar() {
        when(deliverUseCase.deliver("EVT001")).thenReturn(Mono.just(DeliveryOutcome.DELIVERED));

        StepVerifier.create(listener.processBatch(
                        List.of(mensaje("{\"event_id\":\"EVT001\",\"client_id\":\"CLIENT002\"}"))))
                .verifyComplete();

        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    @DisplayName("si el procesamiento falla no se borra, para que SQS lo reentregue")
    void no_borra_lo_que_fallo() {
        StepVerifier.create(listener.processBatch(List.of(mensaje("{no es json}"))))
                .verifyComplete();

        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }
}
