package com.cobre.notifications.infrastructure.security;

import com.cobre.notifications.application.port.out.AccessTokenIssuerPort;
import com.cobre.notifications.domain.model.AccessToken;
import com.cobre.notifications.domain.model.ApiCredential;
import com.cobre.notifications.infrastructure.config.SecurityProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Emite el JWT que despues valida el propio resource server.
 *
 * <p>Se firma con la misma clave simetrica con la que se valida, que es lo coherente
 * cuando emisor y validador son el mismo servicio. Cuando la emision se mueva a un
 * proveedor de identidad, el paso natural son claves asimetricas: el emisor firma con
 * la privada y este servicio valida con la publica, sin conocer ningun secreto.
 *
 * <p>El {@code jti} identifica cada token de forma unica. Hoy no se usa, pero es lo que
 * permitiria construir una lista de revocacion si algun dia hace falta invalidar un
 * token antes de que expire.
 */
@Component
public class NimbusAccessTokenIssuer implements AccessTokenIssuerPort {

    private final byte[] secret;
    private final Duration ttl;
    private final String issuer;
    private final Clock clock;

    public NimbusAccessTokenIssuer(SecurityProperties properties, Clock clock) {
        this.secret = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        this.ttl = properties.jwt().tokenTtl();
        this.issuer = properties.jwt().issuer();
        this.clock = clock;
    }

    @Override
    public AccessToken issue(ApiCredential credential) {
        Instant now = clock.instant();
        Instant expiry = now.plus(ttl);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("api-client-" + credential.clientId())
                .issuer(issuer)
                .claim("client_id", credential.clientId())
                .claim("scope", credential.scopeClaim())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(secret));
        } catch (JOSEException e) {
            // Solo ocurriria con una clave mas corta que el minimo, y eso se valida al
            // arrancar; llegar aqui seria un error de configuracion que ya paso el filtro.
            throw new IllegalStateException("No se pudo firmar el token de acceso", e);
        }

        return new AccessToken(jwt.serialize(), ttl, credential.scopeClaim());
    }
}
