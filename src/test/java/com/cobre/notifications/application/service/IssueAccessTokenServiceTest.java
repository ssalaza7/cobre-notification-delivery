package com.cobre.notifications.application.service;

import com.cobre.notifications.application.port.in.IssueAccessTokenUseCase.ClientCredentials;
import com.cobre.notifications.application.port.out.AccessTokenIssuerPort;
import com.cobre.notifications.application.port.out.ApiCredentialRepositoryPort;
import com.cobre.notifications.application.port.out.MetricsPort;
import com.cobre.notifications.application.port.out.SecretHasherPort;
import com.cobre.notifications.domain.exception.InvalidClientCredentialsException;
import com.cobre.notifications.domain.model.AccessToken;
import com.cobre.notifications.domain.model.ApiCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueAccessTokenServiceTest {

    private static final String CLIENT_ID = "CLIENT002";
    private static final String SECRET = "demo-secret-client002";
    private static final String HASH = "$2a$10$hash-de-ejemplo";

    private final ApiCredentialRepositoryPort credentials = mock(ApiCredentialRepositoryPort.class);
    private final SecretHasherPort hasher = mock(SecretHasherPort.class);
    private final AccessTokenIssuerPort issuer = mock(AccessTokenIssuerPort.class);
    private final MetricsPort metrics = mock(MetricsPort.class);

    private IssueAccessTokenService service;

    private static final ApiCredential CREDENTIAL = new ApiCredential(
            CLIENT_ID, HASH, List.of("notifications:read", "notifications:replay"), true);

    @BeforeEach
    void setUp() {
        service = new IssueAccessTokenService(credentials, hasher, issuer, metrics);
        when(hasher.matchesNothing()).thenReturn(Mono.just(false));
        when(issuer.issue(any())).thenReturn(
                new AccessToken("token-firmado", Duration.ofHours(1), CREDENTIAL.scopeClaim()));
    }

    @Test
    @DisplayName("con credenciales validas emite un token con los permisos de la credencial")
    void emite_token_con_credenciales_validas() {
        when(credentials.findActiveByClientId(CLIENT_ID)).thenReturn(Mono.just(CREDENTIAL));
        when(hasher.matches(SECRET, HASH)).thenReturn(Mono.just(true));

        StepVerifier.create(service.issue(new ClientCredentials(CLIENT_ID, SECRET)))
                .assertNext(token -> {
                    assertThat(token.value()).isEqualTo("token-firmado");
                    assertThat(token.scope()).isEqualTo("notifications:read notifications:replay");
                    assertThat(token.expiresInSeconds()).isEqualTo(3600);
                })
                .verifyComplete();

        verify(metrics).accessTokenIssued();
        verify(metrics, never()).accessTokenDenied();
    }

    @Test
    @DisplayName("un secreto incorrecto se rechaza sin decir por que")
    void rechaza_secreto_incorrecto() {
        when(credentials.findActiveByClientId(CLIENT_ID)).thenReturn(Mono.just(CREDENTIAL));
        when(hasher.matches(anyString(), anyString())).thenReturn(Mono.just(false));

        StepVerifier.create(service.issue(new ClientCredentials(CLIENT_ID, "secreto-equivocado")))
                .expectError(InvalidClientCredentialsException.class)
                .verify();

        verify(metrics).accessTokenDenied();
        verify(issuer, never()).issue(any());
    }

    @Test
    @DisplayName("un cliente inexistente gasta el mismo tiempo: no revela que identificadores existen")
    void un_cliente_inexistente_consume_el_mismo_tiempo() {
        when(credentials.findActiveByClientId("NO-EXISTE")).thenReturn(Mono.empty());

        StepVerifier.create(service.issue(new ClientCredentials("NO-EXISTE", SECRET)))
                .expectError(InvalidClientCredentialsException.class)
                .verify();

        // La verificacion en vacio es lo que iguala los tiempos de respuesta.
        verify(hasher).matchesNothing();
        verify(metrics).accessTokenDenied();
    }

    @Test
    @DisplayName("cliente inexistente y secreto incorrecto producen el mismo error")
    void los_dos_fallos_son_indistinguibles() {
        when(credentials.findActiveByClientId("NO-EXISTE")).thenReturn(Mono.empty());
        when(credentials.findActiveByClientId(CLIENT_ID)).thenReturn(Mono.just(CREDENTIAL));
        when(hasher.matches(anyString(), anyString())).thenReturn(Mono.just(false));

        String porClienteInexistente = mensajeDeError("NO-EXISTE", SECRET);
        String porSecretoIncorrecto = mensajeDeError(CLIENT_ID, "secreto-equivocado");

        assertThat(porClienteInexistente)
                .isNotNull()
                .isEqualTo(porSecretoIncorrecto);
    }

    /** Devuelve el mensaje con el que se rechaza la peticion, o null si fue aceptada. */
    private String mensajeDeError(String clientId, String secret) {
        try {
            service.issue(new ClientCredentials(clientId, secret)).block();
            return null;
        } catch (InvalidClientCredentialsException e) {
            return e.getMessage();
        }
    }

    @Test
    @DisplayName("una credencial desactivada no obtiene token")
    void rechaza_credencial_desactivada() {
        // El repositorio ya filtra por activa, asi que devuelve vacio.
        when(credentials.findActiveByClientId(CLIENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.issue(new ClientCredentials(CLIENT_ID, SECRET)))
                .expectError(InvalidClientCredentialsException.class)
                .verify();
    }

    @Test
    @DisplayName("los permisos salen de la credencial y no de lo que pida el cliente")
    void los_permisos_no_los_elige_el_cliente() {
        ApiCredential soloLectura = new ApiCredential(
                "CLIENT003", HASH, List.of("notifications:read"), true);
        when(credentials.findActiveByClientId("CLIENT003")).thenReturn(Mono.just(soloLectura));
        when(hasher.matches(anyString(), anyString())).thenReturn(Mono.just(true));
        when(issuer.issue(soloLectura)).thenReturn(
                new AccessToken("token-lectura", Duration.ofHours(1), soloLectura.scopeClaim()));

        StepVerifier.create(service.issue(new ClientCredentials("CLIENT003", SECRET)))
                .assertNext(token -> assertThat(token.scope()).isEqualTo("notifications:read"))
                .verifyComplete();
    }
}
