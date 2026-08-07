package com.cobre.notifications.application.service;

import com.cobre.notifications.application.port.out.SecretGeneratorPort;
import com.cobre.notifications.application.port.out.SubscriptionRepositoryPort;
import com.cobre.notifications.application.port.out.WebhookUrlPolicyPort;
import com.cobre.notifications.domain.exception.InvalidWebhookUrlException;
import com.cobre.notifications.domain.exception.SubscriptionNotFoundException;
import com.cobre.notifications.domain.model.Subscription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManageSubscriptionsServiceTest {

    private static final String CLIENTE = "CLIENT002";
    private static final String URL = "https://mi-sistema.com/webhooks";

    private final SubscriptionRepositoryPort subscriptions = mock(SubscriptionRepositoryPort.class);
    private final WebhookUrlPolicyPort urlPolicy = mock(WebhookUrlPolicyPort.class);
    private final SecretGeneratorPort secretGenerator = mock(SecretGeneratorPort.class);

    private final ManageSubscriptionsService service =
            new ManageSubscriptionsService(subscriptions, urlPolicy, secretGenerator);

    private static Subscription existente(String eventType, String secreto) {
        return new Subscription(UUID.randomUUID(), CLIENTE, eventType, "https://viejo.com/h", secreto, true);
    }

    @Test
    @DisplayName("un alta nueva genera su propio secreto de firma")
    void alta_nueva() {
        when(subscriptions.findActiveFor(CLIENTE, "*")).thenReturn(Mono.empty());
        when(secretGenerator.generate()).thenReturn("whsec_nuevo");
        when(subscriptions.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));

        StepVerifier.create(service.register(CLIENTE, "*", URL))
                .assertNext(s -> {
                    assertThat(s.signingSecret()).isEqualTo("whsec_nuevo");
                    assertThat(s.webhookUrl()).isEqualTo(URL);
                    assertThat(s.active()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("actualizar la URL conserva el secreto: rotarlo romperia la verificacion del cliente")
    void actualizar_conserva_el_secreto() {
        when(subscriptions.findActiveFor(CLIENTE, "*")).thenReturn(Mono.just(existente("*", "whsec_viejo")));
        when(subscriptions.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));

        StepVerifier.create(service.register(CLIENTE, "*", URL))
                .assertNext(s -> assertThat(s.signingSecret()).isEqualTo("whsec_viejo"))
                .verifyComplete();

        verify(secretGenerator, never()).generate();
    }

    @Test
    @DisplayName("registrar un tipo concreto no reemplaza el comodin: es una suscripcion nueva")
    void tipo_concreto_no_reemplaza_el_comodin() {
        // findActiveFor devuelve el comodin cuando no hay suscripcion del tipo pedido.
        when(subscriptions.findActiveFor(CLIENTE, "credit_transfer"))
                .thenReturn(Mono.just(existente("*", "whsec_comodin")));
        when(secretGenerator.generate()).thenReturn("whsec_propio");
        when(subscriptions.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));

        StepVerifier.create(service.register(CLIENTE, "credit_transfer", URL))
                .assertNext(s -> {
                    assertThat(s.eventType()).isEqualTo("credit_transfer");
                    assertThat(s.signingSecret()).isEqualTo("whsec_propio");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("un destino invalido se rechaza antes de tocar la base")
    void destino_invalido_no_se_persiste() {
        doThrow(new InvalidWebhookUrlException("El webhook debe usar HTTPS"))
                .when(urlPolicy).validate(anyString());

        StepVerifier.create(service.register(CLIENTE, "*", "http://inseguro.com/h"))
                .expectError(InvalidWebhookUrlException.class)
                .verify();

        verify(subscriptions, never()).save(any());
    }

    @Test
    @DisplayName("el listado va acotado al cliente del token")
    void lista_solo_lo_suyo() {
        when(subscriptions.findAllActiveByClientId(CLIENTE))
                .thenReturn(Flux.just(existente("*", "whsec_x")));

        StepVerifier.create(service.list(CLIENTE)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(subscriptions).findAllActiveByClientId(captor.capture());
        assertThat(captor.getValue()).isEqualTo(CLIENTE);
    }

    @Test
    @DisplayName("dar de baja algo que no existe responde 404, no un exito silencioso")
    void baja_inexistente() {
        when(subscriptions.deactivate(CLIENTE, "no_existe")).thenReturn(Mono.just(false));

        StepVerifier.create(service.deactivate(CLIENTE, "no_existe"))
                .expectError(SubscriptionNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("dar de baja una suscripcion activa la desactiva sin error")
    void baja_correcta() {
        when(subscriptions.deactivate(CLIENTE, "*")).thenReturn(Mono.just(true));

        StepVerifier.create(service.deactivate(CLIENTE, "*")).verifyComplete();
    }
}
