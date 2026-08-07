package com.cobre.notifications.infrastructure.adapter.out.security;

import com.cobre.notifications.application.port.out.SecretGeneratorPort;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Genera el secreto de firma de un webhook.
 *
 * <p>256 bits de {@code SecureRandom}, la fuente criptografica del sistema operativo.
 * {@code Random} no sirve: su salida es predecible conociendo un par de valores previos, y
 * con ese secreto se pueden falsificar notificaciones hacia el cliente.
 */
@Component
public class SecureRandomSecretGenerator implements SecretGeneratorPort {

    private static final int LONGITUD_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String generate() {
        byte[] bytes = new byte[LONGITUD_BYTES];
        random.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
