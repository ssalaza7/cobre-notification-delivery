package com.cobre.notifications.application.service;

import com.cobre.notifications.application.port.in.IssueAccessTokenUseCase;
import com.cobre.notifications.application.port.out.AccessTokenIssuerPort;
import com.cobre.notifications.application.port.out.MetricsPort;
import com.cobre.notifications.domain.exception.InvalidClientCredentialsException;
import com.cobre.notifications.domain.model.AccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Intercambia credenciales de cliente por un token de acceso.
 *
 * <p>El servicio no guarda credenciales ni firma tokens: reenvia lo presentado al
 * proveedor de identidad y devuelve lo que conteste. Administrar identidad pertenece a
 * otro contexto, y no almacenar secretos elimina de raiz la posibilidad de filtrarlos.
 *
 * <p>El endpoint sigue viviendo aqui, y no se expone el del proveedor, por dos razones:
 * el cliente integra contra una sola URL y cambiar de proveedor no le rompe nada; y el
 * limite de tasa, el identificador de peticion y el enmascarado de logs se siguen
 * aplicando sobre este camino.
 *
 * <p><b>Un solo error para todos los casos.</b> Cliente inexistente, secreto incorrecto
 * o credencial desactivada devuelven exactamente lo mismo. Distinguirlos convertiria el
 * endpoint en un directorio de clientes validos. La respuesta en tiempo constante, que
 * antes habia que forzar aqui, ahora la da el proveedor.
 */
public class IssueAccessTokenService implements IssueAccessTokenUseCase {

    private static final Logger log = LoggerFactory.getLogger(IssueAccessTokenService.class);

    private final AccessTokenIssuerPort issuer;
    private final MetricsPort metrics;

    public IssueAccessTokenService(AccessTokenIssuerPort issuer, MetricsPort metrics) {
        this.issuer = issuer;
        this.metrics = metrics;
    }

    @Override
    public Mono<AccessToken> issue(ClientCredentials request) {
        return issuer.issue(request.clientId(), request.clientSecret())
                .doOnNext(token -> {
                    metrics.accessTokenIssued();
                    log.info("Token emitido para el cliente {} con alcance [{}]",
                            request.clientId(), token.scope());
                })
                .onErrorResume(InvalidClientCredentialsException.class,
                        error -> reject(request.clientId(), error));
    }

    private Mono<AccessToken> reject(String clientId, Throwable error) {
        metrics.accessTokenDenied();
        // Se registra el intento fallido para poder alertar por fuerza bruta, pero
        // nunca el secreto presentado.
        log.warn("Intento de autenticacion rechazado para el identificador '{}'", clientId);
        return Mono.error(error);
    }
}
