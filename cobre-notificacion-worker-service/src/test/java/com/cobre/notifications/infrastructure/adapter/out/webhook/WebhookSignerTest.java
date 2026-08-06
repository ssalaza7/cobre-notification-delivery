package com.cobre.notifications.infrastructure.adapter.out.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTest {

    private static final Instant SIGNED_AT = Instant.parse("2024-03-15T12:00:00Z");
    private static final String SECRET = "whsec_test_secret";
    private static final String PAYLOAD = "{\"event_id\":\"EVT001\"}";

    private final WebhookSigner signer = new WebhookSigner();

    @Test
    @DisplayName("la firma es el HMAC-SHA256 en hexadecimal")
    void formato_de_la_firma() {
        String signature = signer.sign(PAYLOAD, SECRET, SIGNED_AT);

        assertThat(signature).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("las cabeceras de firma tienen nombres estables: son contrato publico")
    void las_cabeceras_son_contrato() {
        assertThat(WebhookSigner.TIMESTAMP_HEADER).isEqualTo("X-Cobre-Timestamp");
        assertThat(WebhookSigner.SIGNATURE_HEADER).isEqualTo("X-Cobre-Signature");
    }

    @Test
    @DisplayName("el receptor recalcula la firma sobre timestamp + '.' + cuerpo crudo")
    void el_receptor_puede_verificar() throws Exception {
        String signature = signer.sign(PAYLOAD, SECRET, SIGNED_AT);

        // Lo que haria el cliente en su extremo.
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(
                (SIGNED_AT.getEpochSecond() + "." + PAYLOAD).getBytes(StandardCharsets.UTF_8)));

        assertThat(signature).isEqualTo(expected);
    }

    @Test
    @DisplayName("la marca de tiempo entra en el contenido firmado, no solo en la cabecera")
    void la_marca_de_tiempo_esta_firmada() {
        String first = signer.sign(PAYLOAD, SECRET, SIGNED_AT);
        String second = signer.sign(PAYLOAD, SECRET, SIGNED_AT.plusSeconds(1));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("un secreto distinto produce una firma distinta")
    void el_secreto_cambia_la_firma() {
        assertThat(signer.sign(PAYLOAD, SECRET, SIGNED_AT))
                .isNotEqualTo(signer.sign(PAYLOAD, "otro_secreto", SIGNED_AT));
    }

    @Test
    @DisplayName("la comparacion en tiempo constante distingue firmas y tolera nulos")
    void compara_firmas() {
        String signature = signer.sign(PAYLOAD, SECRET, SIGNED_AT);

        assertThat(signer.matches(signature, signature)).isTrue();
        assertThat(signer.matches(signature, signature + "0")).isFalse();
        assertThat(signer.matches(null, signature)).isFalse();
        assertThat(signer.matches(signature, null)).isFalse();
    }
}
