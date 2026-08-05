package com.cobre.notifications.infrastructure.adapter.out.webhook;

import com.cobre.notifications.domain.exception.InvalidWebhookUrlException;
import com.cobre.notifications.infrastructure.config.WebhookProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookUrlValidatorTest {

    private static WebhookUrlValidator validator(boolean requireHttps, boolean blockInternal) {
        return new WebhookUrlValidator(new WebhookProperties(
                requireHttps, blockInternal, Duration.ofSeconds(2), Duration.ofSeconds(5), 2048, null));
    }

    @Test
    @DisplayName("acepta un destino HTTPS publico")
    void acepta_https_publico() {
        assertThat(validator(true, false).validate("https://cliente.example.com/hooks/1"))
                .hasToString("https://cliente.example.com/hooks/1");
    }

    @Test
    @DisplayName("rechaza HTTP cuando se exige HTTPS: el payload viaja firmado pero en claro")
    void rechaza_http_si_exige_https() {
        assertThatThrownBy(() -> validator(true, false).validate("http://cliente.example.com/hook"))
                .isInstanceOf(InvalidWebhookUrlException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("permite HTTP solo cuando la configuracion lo habilita explicitamente")
    void permite_http_en_local() {
        assertThatCode(() -> validator(false, false).validate("http://localhost:9090/webhooks/CLIENT001"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://cliente.example.com/hook", "file:///etc/passwd", "gopher://x/1"})
    @DisplayName("rechaza esquemas que no son HTTP ni HTTPS")
    void rechaza_esquemas_no_soportados(String url) {
        assertThatThrownBy(() -> validator(false, false).validate(url))
                .isInstanceOf(InvalidWebhookUrlException.class);
    }

    @Test
    @DisplayName("rechaza una URL sin host")
    void rechaza_sin_host() {
        assertThatThrownBy(() -> validator(false, false).validate("https:///solo-ruta"))
                .isInstanceOf(InvalidWebhookUrlException.class)
                .hasMessageContaining("host");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/hook",          // loopback
            "http://localhost:8080/hook",     // loopback por nombre
            "http://10.0.0.5/internal",       // rango privado
            "http://192.168.1.10/internal",   // rango privado
            "http://169.254.169.254/latest/meta-data/"  // metadatos de la nube
    })
    @DisplayName("rechaza destinos internos: es el vector clasico de SSRF")
    void rechaza_direcciones_internas(String url) {
        assertThatThrownBy(() -> validator(false, true).validate(url))
                .isInstanceOf(InvalidWebhookUrlException.class)
                .hasMessageContaining("interna");
    }

    @Test
    @DisplayName("rechaza un host que no resuelve en vez de intentar la peticion")
    void rechaza_host_irresoluble() {
        assertThatThrownBy(() -> validator(false, true)
                .validate("https://host-que-no-existe.invalid/hook"))
                .isInstanceOf(InvalidWebhookUrlException.class)
                .hasMessageContaining("resolver");
    }
}
