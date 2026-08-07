package com.cobre.notifications.application.port.out;

import com.cobre.notifications.domain.exception.InvalidWebhookUrlException;

/**
 * Politica de destinos aceptables para un webhook.
 *
 * <p>Se valida en dos momentos distintos: al <b>registrar</b>, para rechazar de entrada un
 * destino invalido, y al <b>entregar</b>, porque entre uno y otro el DNS pudo cambiar.
 */
public interface WebhookUrlPolicyPort {

    /** @throws InvalidWebhookUrlException si el destino no es aceptable */
    void validate(String url);
}
