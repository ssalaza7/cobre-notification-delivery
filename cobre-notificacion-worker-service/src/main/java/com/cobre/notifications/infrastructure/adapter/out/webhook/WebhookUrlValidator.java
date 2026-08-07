package com.cobre.notifications.infrastructure.adapter.out.webhook;

import com.cobre.notifications.domain.exception.InvalidWebhookUrlException;
import com.cobre.notifications.infrastructure.config.WebhookProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Valida que una URL sea un destino legitimo antes de invocarla.
 *
 * <p>Sin esta validacion el servicio es un proxy: cualquiera que pueda registrar una
 * suscripcion apuntando a {@code http://169.254.169.254/} o a un servicio interno
 * consigue que Cobre haga esa peticion desde dentro de la red y le entregue el
 * resultado. Es SSRF (OWASP A10) y aqui es especialmente relevante porque la URL
 * destino la elige el cliente, no nosotros.
 *
 * <p>La resolucion DNS es bloqueante, asi que el adaptador la ejecuta fuera del
 * event loop de Netty.
 */
@Component
public class WebhookUrlValidator {

    private final WebhookProperties properties;

    public WebhookUrlValidator(WebhookProperties properties) {
        this.properties = properties;
    }

    /**
     * @return la URI validada
     * @throws InvalidWebhookUrlException si el destino no es aceptable
     */
    public URI validate(String url) {
        URI uri = parse(url);
        requireAllowedScheme(uri);
        String host = requireHost(uri);
        if (properties.blockInternalAddresses()) {
            requireExternalAddress(host);
        }
        return uri;
    }

    private URI parse(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new InvalidWebhookUrlException("URL de webhook malformada: " + url);
        }
    }

    private void requireAllowedScheme(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (properties.requireHttps()) {
            if (!"https".equals(scheme)) {
                throw new InvalidWebhookUrlException(
                        "El webhook debe usar HTTPS; se recibio esquema '" + scheme + "'");
            }
            return;
        }
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new InvalidWebhookUrlException("Esquema no soportado: '" + scheme + "'");
        }
    }

    private String requireHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidWebhookUrlException("La URL del webhook no tiene host");
        }
        return host;
    }

    /**
     * Resuelve el host y rechaza cualquier direccion que no sea de internet publica:
     * loopback, enlace local (donde viven los metadatos de las nubes), rango privado,
     * multicast y comodines.
     */
    private void requireExternalAddress(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new InvalidWebhookUrlException("No se pudo resolver el host del webhook: " + host);
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw new InvalidWebhookUrlException(
                        "El webhook apunta a una direccion interna no permitida: " + host);
            }
        }
    }

    private boolean isInternal(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress();
    }
}
