package com.cobre.notifications.infrastructure.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un secreto que llega al log es un secreto filtrado: no se puede borrar de los indices
 * ya replicados y obliga a rotarlo.
 */
class SensitiveDataMaskerTest {

    @Test
    @DisplayName("tapa el valor de los parametros sensibles y conserva el resto")
    void tapa_lo_sensible() {
        String masked = SensitiveDataMasker.maskQuery(
                "/notification_events", "delivery_status=failed&client_secret=super-secreto&page=0");

        assertThat(masked)
                .isEqualTo("/notification_events?delivery_status=failed&client_secret=***&page=0");
    }

    @Test
    @DisplayName("conserva el nombre del parametro: saber que alguien lo mando es lo que permite avisarle")
    void conserva_el_nombre() {
        assertThat(SensitiveDataMasker.maskQuery("/x", "access_token=abc"))
                .isEqualTo("/x?access_token=***");
    }

    @Test
    @DisplayName("reconoce las variantes: client_secret, signing_secret, api_key, password, signature")
    void reconoce_las_variantes() {
        assertThat(SensitiveDataMasker.maskQuery("/x", "signing_secret=a")).endsWith("signing_secret=***");
        assertThat(SensitiveDataMasker.maskQuery("/x", "API_KEY=a")).endsWith("API_KEY=***");
        assertThat(SensitiveDataMasker.maskQuery("/x", "password=a")).endsWith("password=***");
        assertThat(SensitiveDataMasker.maskQuery("/x", "X-Signature=a")).endsWith("X-Signature=***");
        assertThat(SensitiveDataMasker.maskQuery("/x", "Authorization=Bearer+x")).endsWith("Authorization=***");
    }

    @Test
    @DisplayName("sin query devuelve la ruta tal cual")
    void sin_query() {
        assertThat(SensitiveDataMasker.maskQuery("/notification_events", null))
                .isEqualTo("/notification_events");
        assertThat(SensitiveDataMasker.maskQuery("/notification_events", ""))
                .isEqualTo("/notification_events");
    }

    @Test
    @DisplayName("un parametro sin '=' no rompe el enmascarado")
    void parametro_sin_valor() {
        assertThat(SensitiveDataMasker.maskQuery("/x", "flag&token=a"))
                .isEqualTo("/x?flag&token=***");
    }
}
