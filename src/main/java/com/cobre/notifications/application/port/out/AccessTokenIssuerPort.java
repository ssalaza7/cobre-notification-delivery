package com.cobre.notifications.application.port.out;

import com.cobre.notifications.domain.model.AccessToken;
import com.cobre.notifications.domain.model.ApiCredential;

/**
 * Puerto de salida que firma el token.
 *
 * <p>Existe como puerto para que el caso de uso no sepa que hoy se firma un JWT con
 * clave simetrica. Migrar a claves asimetricas, o a delegar la emision en un proveedor
 * de identidad, es reemplazar este adaptador.
 */
public interface AccessTokenIssuerPort {

    AccessToken issue(ApiCredential credential);
}
