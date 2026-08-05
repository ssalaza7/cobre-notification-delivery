package com.cobre.notifications.infrastructure.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Locale;
import java.util.Map;

/**
 * Activa un componente solo cuando la instancia cumple un rol.
 *
 * <p>El servicio es <b>un solo microservicio</b> —un bounded context, un modelo, una
 * base de datos— pero se despliega dos veces con roles distintos:
 *
 * <ul>
 *   <li>{@code api}: expone los endpoints self-service detras del balanceador.</li>
 *   <li>{@code worker}: consume el bus y entrega los webhooks. Sin trafico entrante.</li>
 *   <li>{@code all}: ambos en el mismo proceso. Es el valor por omision y existe para
 *       desarrollo local y para las pruebas.</li>
 * </ul>
 *
 * <p><b>Por que dos despliegues y no dos servicios.</b> Sus perfiles de carga no se
 * parecen: la API sigue el trafico de personas y paneles, el worker sigue el ritmo de
 * la plataforma y puede tener que drenar millones de eventos de madrugada. Escalarlos
 * juntos obliga a pagar worker de mas o a quedarse corto de API. Y si la API se satura
 * el cliente no consulta, mientras que si se satura el worker no se entregan
 * notificaciones: son fallos de gravedad distinta y merecen aislarse.
 *
 * <p>Partirlos en dos <i>servicios</i>, en cambio, seria un error: comparten las mismas
 * tablas y la misma maquina de estados. Dos servicios contra una misma base de datos
 * es un monolito distribuido, que paga el costo de la red sin obtener el aislamiento.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Conditional(ConditionalOnRole.OnRoleCondition.class)
public @interface ConditionalOnRole {

    String PROPERTY = "cobre.role";
    String API = "api";
    String WORKER = "worker";
    String ALL = "all";

    /** Rol que debe cumplir la instancia para que el componente se registre. */
    String value();

    class OnRoleCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Map<String, Object> attributes =
                    metadata.getAnnotationAttributes(ConditionalOnRole.class.getName());
            if (attributes == null) {
                return true;
            }
            String required = String.valueOf(attributes.get("value"));
            String configured = context.getEnvironment()
                    .getProperty(PROPERTY, ALL)
                    .trim()
                    .toLowerCase(Locale.ROOT);

            return ALL.equals(configured) || configured.equals(required);
        }
    }
}
