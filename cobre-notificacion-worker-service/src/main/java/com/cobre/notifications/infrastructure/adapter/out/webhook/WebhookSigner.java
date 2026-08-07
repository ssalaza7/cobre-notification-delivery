package com.cobre.notifications.infrastructure.adapter.out.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Firma HMAC-SHA256 del payload saliente.
 *
 * <p>Sin firma, el receptor no tiene forma de distinguir una notificacion de Cobre de
 * una peticion fabricada por cualquiera que conozca la URL del webhook. Con ella,
 * el cliente recalcula el HMAC con su secreto compartido y descarta lo que no cuadre.
 *
 * <p>Se firma la concatenacion {@code timestamp + "." + cuerpo crudo} con HMAC-SHA256
 * sobre UTF-8. Es el esquema estandar de la industria para firmar webhooks, y se elige
 * por dos propiedades concretas.
 *
 * <p>Primero, se firma el <b>cuerpo crudo</b> y no un objeto ya deserializado: dos
 * bibliotecas JSON distintas pueden reordenar claves o normalizar espacios, y entonces
 * el receptor calcularia un hash distinto sobre el mismo contenido.
 *
 * <p>Segundo, el instante entra <i>dentro</i> del contenido firmado y no solo en una
 * cabecera suelta: eso permite al receptor rechazar la reproduccion de una captura
 * antigua sin que un atacante pueda alterar la marca de tiempo.
 */
@Component
public class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    /** Cabecera con el instante de firma, en segundos desde epoch (UTC). */
    public static final String TIMESTAMP_HEADER = "X-Cobre-Timestamp";

    /** Cabecera con el HMAC-SHA256 en hexadecimal. */
    public static final String SIGNATURE_HEADER = "X-Cobre-Signature";

    /**
     * Calcula el HMAC de {@code timestamp + "." + payload}.
     *
     * @return el hash en hexadecimal, tal como debe viajar en la cabecera de firma
     */
    public String sign(String payload, String secret, Instant timestamp) {
        return hmacHex(timestamp.getEpochSecond() + "." + payload, secret);
    }

    private String hmacHex(String content, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // Solo ocurriria si la JVM no trae HmacSHA256, que es parte del estandar.
            throw new IllegalStateException("No se pudo calcular la firma HMAC", e);
        }
    }

    /**
     * Comparacion en tiempo constante, para verificar una firma sin filtrar por
     * temporizacion cuantos bytes coincidian.
     */
    public boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
