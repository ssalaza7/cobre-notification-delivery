package com.cobre.notifications.application.service;

import com.cobre.notifications.application.port.in.IssueAccessTokenUseCase.ClientCredentials;
import com.cobre.notifications.application.port.out.AccessTokenIssuerPort;
import com.cobre.notifications.application.port.out.MetricsPort;
import com.cobre.notifications.domain.exception.InvalidClientCredentialsException;
import com.cobre.notifications.domain.model.AccessToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El caso de uso ya no verifica credenciales: las reenvia al proveedor de identidad.
 *
 * <p>Lo que sigue siendo responsabilidad suya, y es lo que se prueba, es no filtrar en
 * que fallo la autenticacion y dejar rastro de los intentos rechazados para poder
 * alertar por fuerza bruta.
 */
class IssueAccessTokenServiceTest {

    private static final String CLIENT_ID = "CLIENT001";
    private static final String SECRET = "un-secreto-cualquiera";

    private final AccessTokenIssuerPort issuer = mock(AccessTokenIssuerPort.class);
    private final MetricsPort metrics = mock(MetricsPort.class);

    private final IssueAccessTokenService service = new IssueAccessTokenService(issuer, metrics);

    @Test
    @DisplayName("reenvia las credenciales presentadas y devuelve el token del proveedor")
    void reenvia_y_devuelve_el_token() {
        AccessToken emitido = new AccessToken("el-jwt", Duration.ofHours(1), "notifications:read");
        when(issuer.issue(CLIENT_ID, SECRET)).thenReturn(Mono.just(emitido));

        StepVerifier.create(service.issue(new ClientCredentials(CLIENT_ID, SECRET)))
                .expectNext(emitido)
                .verifyComplete();

        verify(issuer).issue(CLIENT_ID, SECRET);
        verify(metrics).accessTokenIssued();
    }

    @Test
    @DisplayName("un rechazo del proveedor se cuenta y se propaga sin detallar la causa")
    void propaga_el_rechazo() {
        when(issuer.issue(anyString(), anyString()))
                .thenReturn(Mono.error(new InvalidClientCredentialsException()));

        StepVerifier.create(service.issue(new ClientCredentials(CLIENT_ID, "secreto-erroneo")))
                .expectError(InvalidClientCredentialsException.class)
                .verify();

        verify(metrics).accessTokenDenied();
        verify(metrics, never()).accessTokenIssued();
    }

    @Test
    @DisplayName("un cliente inexistente responde igual que un secreto incorrecto")
    void no_distingue_cliente_de_secreto() {
        when(issuer.issue(anyString(), anyString()))
                .thenReturn(Mono.error(new InvalidClientCredentialsException()));

        StepVerifier.create(service.issue(new ClientCredentials("NO_EXISTE", SECRET)))
                .expectError(InvalidClientCredentialsException.class)
                .verify();

        StepVerifier.create(service.issue(new ClientCredentials(CLIENT_ID, "otro")))
                .expectError(InvalidClientCredentialsException.class)
                .verify();
    }
}
