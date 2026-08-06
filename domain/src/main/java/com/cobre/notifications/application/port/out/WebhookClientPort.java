package com.cobre.notifications.application.port.out;

import com.cobre.notifications.domain.model.WebhookDeliveryRequest;
import com.cobre.notifications.domain.model.WebhookDeliveryResult;
import reactor.core.publisher.Mono;

/**
 * Puerto de salida hacia el webhook del cliente.
 *
 * <p>El contrato es no lanzar: cualquier fallo de red, timeout o codigo de error se
 * traduce a un {@link WebhookDeliveryResult}. Asi el caso de uso siempre tiene un
 * resultado que registrar en la bitacora, incluso cuando el destino ni siquiera
 * respondio.
 */
public interface WebhookClientPort {

    Mono<WebhookDeliveryResult> deliver(WebhookDeliveryRequest request);
}
