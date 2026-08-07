package com.cobre.notifications.infrastructure.security;

import java.time.Duration;

import com.cobre.notifications.application.port.out.AccessTokenIssuerPort;
import com.cobre.notifications.domain.exception.InvalidClientCredentialsException;
import com.cobre.notifications.domain.model.AccessToken;
import com.cobre.notifications.infrastructure.config.SecurityProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Pide el token al proveedor de identidad con el flujo {@code client_credentials}.
 *
 * <p>Las credenciales atraviesan este servicio pero no se guardan en ningun sitio: ni
 * en base, ni en log —el enmascarado las tapa antes de escribir— ni en la respuesta.
 * Solo se reenvian.
 *
 * <p>El proveedor decide los alcances a partir de lo que tenga registrado para ese
 * cliente. El servicio no los pide ni los puede ampliar, que es lo mismo que garantizaba
 * antes leerlos de la credencial en vez de la peticion.
 */
public class OidcAccessTokenIssuer implements AccessTokenIssuerPort {

    private static final String GRANT_TYPE = "client_credentials";

    private final WebClient webClient;
    private final String tokenUri;

    public OidcAccessTokenIssuer(WebClient webClient, SecurityProperties properties) {
        this.webClient = webClient;
        this.tokenUri = properties.oidc().tokenUri();
    }

    @Override
    public Mono<AccessToken> issue(String clientId, String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT_TYPE);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        return webClient.post()
                .uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                // 400 y 401 son la respuesta del estandar a unas credenciales que no
                // valen. Se traducen al mismo error de dominio que cualquier otro fallo
                // de autenticacion, para no revelar en cual de los dos casos se cayo.
                .onStatus(HttpStatusCode::is4xxClientError,
                        response -> response.releaseBody()
                                .then(Mono.error(new InvalidClientCredentialsException())))
                .bodyToMono(TokenResponse.class)
                .map(TokenResponse::toDomain);
    }

    /** Respuesta estandar del endpoint de token, con los campos que interesan. */
    private record TokenResponse(String access_token, long expires_in, String scope) {

        AccessToken toDomain() {
            return new AccessToken(access_token, Duration.ofSeconds(expires_in), scope);
        }
    }
}
