package com.cobre.notifications.application.port.out;

/**
 * Genera el secreto con el que se firmara el payload saliente de un cliente.
 *
 * <p>Es un puerto y no una llamada directa a {@code SecureRandom} para poder probar los
 * casos de uso con un valor fijo y para poder cambiar la fuente —a KMS, por ejemplo— sin
 * tocar la logica.
 */
public interface SecretGeneratorPort {

    String generate();
}
