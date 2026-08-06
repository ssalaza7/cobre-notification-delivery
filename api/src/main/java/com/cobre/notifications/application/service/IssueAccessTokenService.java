package com.cobre.notifications.application.service;

import com.cobre.notifications.application.port.in.IssueAccessTokenUseCase;
import com.cobre.notifications.application.port.out.AccessTokenIssuerPort;
import com.cobre.notifications.application.port.out.ApiCredentialRepositoryPort;
import com.cobre.notifications.application.port.out.MetricsPort;
import com.cobre.notifications.application.port.out.SecretHasherPort;
import com.cobre.notifications.domain.exception.InvalidClientCredentialsException;
import com.cobre.notifications.domain.model.AccessToken;
import com.cobre.notifications.domain.model.ApiCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Intercambia credenciales de cliente por un token de acceso.
 *
 * <p>Tres decisiones sostienen la seguridad de este caso de uso:
 *
 * <p><b>Un solo error para todos los casos.</b> Cliente inexistente, secreto
 * incorrecto o credencial desactivada devuelven exactamente lo mismo. Distinguirlos
 * convertiria el endpoint en un directorio de clientes validos.
 *
 * <p><b>Tiempo de respuesta constante.</b> Si el cliente no existe se ejecuta igual
 * una verificacion en vacio. Sin eso, un cliente inexistente responderia en
 * milisegundos y uno real tardaria lo que tarda el hash: esa diferencia es suficiente
 * para enumerar identificadores validos y convertir un ataque ciego en uno dirigido.
 *
 * <p><b>Los permisos salen de la credencial, no de la peticion.</b> El cliente pide un
 * token; no elige su alcance.
 */
public class IssueAccessTokenService implements IssueAccessTokenUseCase {

    private static final Logger log = LoggerFactory.getLogger(IssueAccessTokenService.class);

    private final ApiCredentialRepositoryPort credentials;
    private final SecretHasherPort hasher;
    private final AccessTokenIssuerPort issuer;
    private final MetricsPort metrics;

    public IssueAccessTokenService(
            ApiCredentialRepositoryPort credentials,
            SecretHasherPort hasher,
            AccessTokenIssuerPort issuer,
            MetricsPort metrics) {
        this.credentials = credentials;
        this.hasher = hasher;
        this.issuer = issuer;
        this.metrics = metrics;
    }

    @Override
    public Mono<AccessToken> issue(ClientCredentials request) {
        return credentials.findActiveByClientId(request.clientId())
                .flatMap(credential -> verify(credential, request.clientSecret()))
                // Cliente inexistente o desactivado: se gasta el mismo tiempo igual.
                .switchIfEmpty(Mono.defer(() -> hasher.matchesNothing().then(Mono.empty())))
                .switchIfEmpty(Mono.defer(() -> reject(request.clientId())));
    }

    private Mono<AccessToken> verify(ApiCredential credential, String presentedSecret) {
        return hasher.matches(presentedSecret, credential.secretHash())
                .flatMap(matches -> {
                    if (!matches) {
                        return Mono.empty();
                    }
                    AccessToken token = issuer.issue(credential);
                    metrics.accessTokenIssued();
                    log.info("Token emitido para el cliente {} con alcance [{}]",
                            credential.clientId(), credential.scopeClaim());
                    return Mono.just(token);
                });
    }

    private Mono<AccessToken> reject(String clientId) {
        metrics.accessTokenDenied();
        // Se registra el intento fallido para poder alertar por fuerza bruta, pero
        // nunca el secreto presentado.
        log.warn("Intento de autenticacion rechazado para el identificador '{}'", clientId);
        return Mono.error(new InvalidClientCredentialsException());
    }
}
