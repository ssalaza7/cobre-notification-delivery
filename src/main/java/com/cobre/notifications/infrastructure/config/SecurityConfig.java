package com.cobre.notifications.infrastructure.config;

import com.cobre.notifications.infrastructure.security.RateLimitWebFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Seguridad de la API self-service.
 *
 * <p>Decisiones y por que:
 * <ul>
 *   <li><b>Todo cerrado por defecto</b>: la ultima regla es {@code denyAll()}. Un
 *       endpoint nuevo nace protegido; si la ultima regla fuera {@code permitAll()},
 *       nacer sin proteger seria el comportamiento por omision.</li>
 *   <li><b>Autorizacion por scope y no solo por autenticacion</b>: leer y reenviar son
 *       permisos distintos. Un token de un panel de consulta no deberia poder disparar
 *       reenvios.</li>
 *   <li><b>CSRF desactivado</b>: la API es stateless y se autentica con Bearer, no con
 *       cookies. Sin cookies de sesion no hay vector CSRF que proteger.</li>
 *   <li><b>Actuator</b>: solo {@code health} e {@code info} son publicos, porque los
 *       necesitan las sondas de Kubernetes. Metricas y demas exigen scope propio; en
 *       produccion lo natural es ademas moverlos a un puerto de gestion que no se
 *       publique a internet.</li>
 * </ul>
 *
 * <p><b>Este servicio valida tokens; no los emite.</b> Emitir identidad pertenece a
 * otro contexto: un servicio de notificaciones no deberia administrar credenciales.
 *
 * <p>La clave HS256 compartida es adecuada para esta prueba porque permite correr el
 * proyecto sin desplegar un emisor. El paso natural es validar por JWKS contra un
 * proveedor OIDC -Amazon Cognito en el despliegue propuesto-, que rota claves sin
 * redesplegar y evita que el servicio conozca ningun secreto de firma.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final String SCOPE_READ = "SCOPE_notifications:read";
    private static final String SCOPE_REPLAY = "SCOPE_notifications:replay";
    private static final String SCOPE_MONITOR = "SCOPE_notifications:monitor";

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, RateLimitWebFilter rateLimitFilter) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // Unico endpoint publico: es donde el cliente prueba su
                        // identidad, asi que no puede exigir identidad previa.
                        .pathMatchers(HttpMethod.POST, "/oauth/token").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/actuator/**").hasAuthority(SCOPE_MONITOR)
                        .pathMatchers(HttpMethod.POST, "/notification_events/*/replay").hasAuthority(SCOPE_REPLAY)
                        .pathMatchers(HttpMethod.GET, "/notification_events/**").hasAuthority(SCOPE_READ)
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                // El limitador va despues de la autorizacion: solo tiene sentido contar
                // peticiones de clientes ya identificados.
                .addFilterAfter(rateLimitFilter, SecurityWebFiltersOrder.AUTHORIZATION)
                .headers(headers -> headers
                        .hsts(hsts -> hsts.maxAge(Duration.ofDays(365)).includeSubdomains(true))
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.mode(
                                org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter
                                        .Mode.DENY)))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(SecurityProperties properties) {
        byte[] secret = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            // HS256 con una clave mas corta que el tamano del hash es fuerza bruta viable.
            throw new IllegalStateException(
                    "cobre.security.jwt.secret debe tener al menos 32 bytes para HS256");
        }
        return NimbusReactiveJwtDecoder
                .withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
