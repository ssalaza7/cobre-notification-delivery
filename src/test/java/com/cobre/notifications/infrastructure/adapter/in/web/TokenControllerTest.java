package com.cobre.notifications.infrastructure.adapter.in.web;

import com.cobre.notifications.application.port.in.IssueAccessTokenUseCase;
import com.cobre.notifications.application.port.in.IssueAccessTokenUseCase.ClientCredentials;
import com.cobre.notifications.domain.exception.InvalidClientCredentialsException;
import com.cobre.notifications.domain.model.AccessToken;
import com.cobre.notifications.infrastructure.adapter.in.web.dto.TokenRequest;
import com.cobre.notifications.infrastructure.adapter.in.web.dto.TokenResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenControllerTest {

    private final IssueAccessTokenUseCase useCase = mock(IssueAccessTokenUseCase.class);
    private final TokenController controller = new TokenController(useCase);

    private static final TokenRequest PETICION =
            new TokenRequest("client_credentials", "CLIENT002", "demo-secret-client002");

    @Test
    @DisplayName("devuelve el token con la forma estandar de OAuth2")
    void devuelve_la_respuesta_estandar() {
        when(useCase.issue(any())).thenReturn(Mono.just(
                new AccessToken("token-firmado", Duration.ofHours(1), "notifications:read")));

        StepVerifier.create(controller.issue(PETICION))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    TokenResponse body = response.getBody();
                    assertThat(body).isNotNull();
                    assertThat(body.accessToken()).isEqualTo("token-firmado");
                    assertThat(body.tokenType()).isEqualTo("Bearer");
                    assertThat(body.expiresIn()).isEqualTo(3600);
                    assertThat(body.scope()).isEqualTo("notifications:read");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("marca la respuesta como no cacheable: un token en una cache compartida es un token regalado")
    void la_respuesta_no_se_cachea() {
        when(useCase.issue(any())).thenReturn(Mono.just(
                new AccessToken("token-firmado", Duration.ofHours(1), "notifications:read")));

        ResponseEntity<TokenResponse> response = controller.issue(PETICION).block();

        assertThat(response).isNotNull();
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    @DisplayName("traslada las credenciales tal como llegan, sin scopes de la peticion")
    void traslada_las_credenciales() {
        when(useCase.issue(any())).thenReturn(Mono.just(
                new AccessToken("t", Duration.ofHours(1), "notifications:read")));

        controller.issue(PETICION).block();

        ArgumentCaptor<ClientCredentials> captor = ArgumentCaptor.forClass(ClientCredentials.class);
        verify(useCase).issue(captor.capture());
        assertThat(captor.getValue().clientId()).isEqualTo("CLIENT002");
        assertThat(captor.getValue().clientSecret()).isEqualTo("demo-secret-client002");
    }

    @Test
    @DisplayName("propaga el rechazo de credenciales para que el manejador lo traduzca a 401")
    void propaga_el_rechazo() {
        when(useCase.issue(any())).thenReturn(Mono.error(new InvalidClientCredentialsException()));

        StepVerifier.create(controller.issue(PETICION))
                .expectError(InvalidClientCredentialsException.class)
                .verify();
    }

    @Test
    @DisplayName("el DTO expone las credenciales al dominio sin arrastrar nombres de OAuth2")
    void el_dto_traduce_al_dominio() {
        ClientCredentials credentials = PETICION.toCredentials();

        assertThat(credentials.clientId()).isEqualTo("CLIENT002");
        assertThat(credentials.clientSecret()).isEqualTo("demo-secret-client002");
        assertThat(PETICION.grantType()).isEqualTo("client_credentials");
    }
}
