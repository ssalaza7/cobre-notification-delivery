package com.cobre.notifications.infrastructure.security;

import com.cobre.notifications.application.port.out.SecretHasherPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Verificacion de secretos con bcrypt.
 *
 * <p>bcrypt es lento <b>a proposito</b>: esa lentitud es justamente su valor, porque
 * encarece la fuerza bruta contra un volcado de la base. La contrapartida es que
 * bloquea el hilo mientras calcula, asi que en WebFlux hay que sacarlo del event loop
 * de Netty; si no, unas pocas peticiones de token simultaneas congelarian el servidor
 * entero.
 */
@Component
public class BCryptSecretHasherAdapter implements SecretHasherPort {

    /**
     * Hash de un valor cualquiera, usado solo para consumir tiempo cuando el cliente no
     * existe. Es un bcrypt valido, asi que el calculo cuesta exactamente lo mismo que
     * una verificacion real.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public Mono<Boolean> matches(String rawSecret, String hash) {
        if (rawSecret == null || hash == null) {
            return matchesNothing();
        }
        return Mono.fromCallable(() -> encoder.matches(rawSecret, hash))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> matchesNothing() {
        return Mono.fromCallable(() -> encoder.matches("secreto-que-no-coincide", DUMMY_HASH))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(false);
    }
}
