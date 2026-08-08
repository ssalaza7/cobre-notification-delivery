package com.cobre.notifications.infrastructure.adapter.out.security;

import com.cobre.notifications.application.port.out.SigningSecretStorePort;
import com.cobre.notifications.infrastructure.config.SecretsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceExistsException;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

/**
 * Secretos de firma sobre AWS Secrets Manager.
 *
 * <p>El nombre se deriva del cliente y el tipo de evento, de modo que la referencia es
 * reconstruible y los permisos se pueden acotar por prefijo: la api solo crea bajo el
 * suyo, el worker solo lee.
 */
public class SecretsManagerSigningSecretStore implements SigningSecretStorePort {

    private static final Logger log = LoggerFactory.getLogger(SecretsManagerSigningSecretStore.class);

    private final SecretsManagerAsyncClient secrets;
    private final String prefix;

    public SecretsManagerSigningSecretStore(
            SecretsManagerAsyncClient secrets, SecretsProperties properties) {
        this.secrets = secrets;
        this.prefix = properties.signingKeyPrefix();
    }

    @Override
    public Mono<String> store(String clientId, String eventType, String secret) {
        String name = nameFor(clientId, eventType);

        return Mono.fromFuture(() -> secrets.createSecret(CreateSecretRequest.builder()
                        .name(name)
                        .secretString(secret)
                        .description("Clave HMAC con la que se firman los webhooks de " + clientId)
                        .build()))
                .thenReturn(name)
                // Ya existia: se conserva el valor guardado. Rotarlo al cambiar la URL
                // rompería la verificacion de firma del cliente sin avisarle.
                .onErrorResume(ResourceExistsException.class, error -> {
                    log.debug("El secreto {} ya existia; se conserva su valor", name);
                    return Mono.just(name);
                });
    }

    @Override
    public Mono<String> read(String reference) {
        return Mono.fromFuture(() -> secrets.getSecretValue(
                        GetSecretValueRequest.builder().secretId(reference).build()))
                .map(response -> response.secretString())
                // Una referencia rota no debe tumbar la entrega del resto: se registra y
                // el evento acaba descartado por falta de suscripcion utilizable.
                .onErrorResume(ResourceNotFoundException.class, error -> {
                    log.error("La suscripcion apunta al secreto {}, que no existe", reference);
                    return Mono.empty();
                });
    }

    /**
     * Nombre derivado, no aleatorio: permite acotar los permisos por prefijo y volver a
     * encontrarlo si alguna vez hubiera que reconciliar tabla y almacen.
     */
    private String nameFor(String clientId, String eventType) {
        String tipo = "*".equals(eventType) ? "todos" : eventType;
        return prefix + clientId + "/" + tipo;
    }
}
