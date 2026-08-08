package com.cobre.notifications.infrastructure.adapter.out.security;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.cobre.notifications.infrastructure.config.SecretsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretResponse;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceExistsException;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecretsManagerSigningSecretStoreTest {

    private final SecretsManagerAsyncClient client = mock(SecretsManagerAsyncClient.class);
    private final SecretsProperties properties =
            new SecretsProperties("us-east-1", "http://localhost:4566", "cobre/firma/", Duration.ofSeconds(60));

    private final SecretsManagerSigningSecretStore store =
            new SecretsManagerSigningSecretStore(client, properties);

    @Test
    @DisplayName("el nombre se deriva del cliente y el tipo, bajo el prefijo configurado")
    void deriva_el_nombre() {
        when(client.createSecret(any(CreateSecretRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(CreateSecretResponse.builder().build()));

        StepVerifier.create(store.store("CLIENT002", "credit_transfer", "whsec_x"))
                .expectNext("cobre/firma/CLIENT002/credit_transfer")
                .verifyComplete();

        ArgumentCaptor<CreateSecretRequest> peticion = ArgumentCaptor.forClass(CreateSecretRequest.class);
        org.mockito.Mockito.verify(client).createSecret(peticion.capture());
        assertThat(peticion.getValue().secretString()).isEqualTo("whsec_x");
    }

    @Test
    @DisplayName("el comodin no viaja como '*' al nombre del secreto")
    void el_comodin_se_traduce() {
        when(client.createSecret(any(CreateSecretRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(CreateSecretResponse.builder().build()));

        StepVerifier.create(store.store("CLIENT002", "*", "whsec_x"))
                .expectNext("cobre/firma/CLIENT002/todos")
                .verifyComplete();
    }

    @Test
    @DisplayName("si ya existe se conserva el valor guardado, no se sobrescribe")
    void conserva_el_secreto_existente() {
        // Rotarlo al cambiar la URL rompería la verificacion de firma del cliente.
        when(client.createSecret(any(CreateSecretRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        ResourceExistsException.builder().message("ya existe").build()));

        StepVerifier.create(store.store("CLIENT002", "*", "whsec_nuevo"))
                .expectNext("cobre/firma/CLIENT002/todos")
                .verifyComplete();
    }

    @Test
    @DisplayName("devuelve el valor guardado")
    void lee_el_secreto() {
        when(client.getSecretValue(any(software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetSecretValueResponse.builder().secretString("whsec_x").build()));

        StepVerifier.create(store.read("cobre/firma/CLIENT002/todos"))
                .expectNext("whsec_x")
                .verifyComplete();
    }

    @Test
    @DisplayName("una referencia rota devuelve vacio en vez de tumbar la entrega")
    void referencia_rota() {
        when(client.getSecretValue(any(software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        ResourceNotFoundException.builder().message("no existe").build()));

        StepVerifier.create(store.read("cobre/firma/CLIENT002/todos"))
                .verifyComplete();
    }
}
