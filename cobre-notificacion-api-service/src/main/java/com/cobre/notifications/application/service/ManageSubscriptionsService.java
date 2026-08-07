package com.cobre.notifications.application.service;

import com.cobre.notifications.application.port.in.ManageSubscriptionsUseCase;
import com.cobre.notifications.application.port.out.SecretGeneratorPort;
import com.cobre.notifications.application.port.out.SubscriptionRepositoryPort;
import com.cobre.notifications.application.port.out.WebhookUrlPolicyPort;
import com.cobre.notifications.domain.exception.SubscriptionNotFoundException;
import com.cobre.notifications.domain.model.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Alta, listado y baja de los webhooks de un cliente.
 *
 * <p>El {@code clientId} llega siempre del token, nunca del cuerpo de la peticion: si
 * viniera en el cuerpo, cualquiera podria registrar un webhook a nombre de otro y
 * desviarse sus notificaciones.
 */
public class ManageSubscriptionsService implements ManageSubscriptionsUseCase {

    private final SubscriptionRepositoryPort subscriptions;
    private final WebhookUrlPolicyPort urlPolicy;
    private final SecretGeneratorPort secretGenerator;

    public ManageSubscriptionsService(
            SubscriptionRepositoryPort subscriptions,
            WebhookUrlPolicyPort urlPolicy,
            SecretGeneratorPort secretGenerator) {
        this.subscriptions = subscriptions;
        this.urlPolicy = urlPolicy;
        this.secretGenerator = secretGenerator;
    }

    @Override
    public Mono<Subscription> register(String clientId, String eventType, String webhookUrl) {
        // Se valida antes de tocar la base: un destino invalido no llega a persistirse y
        // el cliente se entera al registrar, no cuando falle la primera entrega real.
        // Todo va diferido a proposito. Con `then(subscriptions.findActiveFor(...))` la
        // consulta se arma antes de validar, y con `defaultIfEmpty(Subscription.register(
        // ...))` se genera un secreto en cada alta aunque se trate de una actualizacion.
        return Mono.<Void>fromRunnable(() -> urlPolicy.validate(webhookUrl))
                .then(Mono.defer(() -> subscriptions.findActiveFor(clientId, eventType)))
                .map(existente -> conservarSecreto(existente, clientId, eventType, webhookUrl))
                .switchIfEmpty(Mono.fromSupplier(() -> Subscription.register(
                        clientId, eventType, webhookUrl, secretGenerator.generate())))
                .flatMap(subscriptions::save);
    }

    /**
     * Al actualizar se conserva el secreto: rotarlo en cada cambio de URL romperia la
     * verificacion de firma del cliente sin avisarle.
     *
     * <p>{@code findActiveFor} puede devolver el comodin cuando se consulta un tipo
     * concreto. En ese caso lo que se pide es una suscripcion nueva, no reemplazar el
     * comodin.
     */
    private Subscription conservarSecreto(
            Subscription existente, String clientId, String eventType, String webhookUrl) {
        return existente.eventType().equals(eventType)
                ? new Subscription(existente.id(), clientId, eventType, webhookUrl,
                        existente.signingSecret(), true)
                : Subscription.register(clientId, eventType, webhookUrl, secretGenerator.generate());
    }

    @Override
    public Flux<Subscription> list(String clientId) {
        return subscriptions.findAllActiveByClientId(clientId);
    }

    @Override
    public Mono<Void> deactivate(String clientId, String eventType) {
        return subscriptions.deactivate(clientId, eventType)
                .flatMap(existia -> Boolean.TRUE.equals(existia)
                        ? Mono.empty()
                        : Mono.error(new SubscriptionNotFoundException(eventType)));
    }
}
