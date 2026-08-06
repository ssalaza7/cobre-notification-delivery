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
 * Elige que tecnologia de mensajeria se activa.
 *
 * <p>El servicio necesita dos cosas distintas del sustrato, y esta anotacion decide con
 * que se cumplen:
 *
 * <ul>
 *   <li><b>{@code rabbit}</b>: un solo broker hace de bus de eventos y de cola de
 *       trabajo. Es lo mas simple de levantar en una maquina.</li>
 *   <li><b>{@code aws}</b>: <b>Kafka</b> como bus de eventos y <b>SQS</b> como cola de
 *       trabajo, que es la arquitectura objetivo. En local se emulan con Redpanda y
 *       ElasticMQ, que hablan los mismos protocolos.</li>
 * </ul>
 *
 * <p>Que las dos convivan no es indecision: es la prueba de que los puertos hacen su
 * trabajo. El dominio, los casos de uso y sus pruebas son identicos con cualquiera de
 * los dos, porque ninguno de ellos sabe que existe un broker.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Conditional(MessagingProvider.OnProviderCondition.class)
public @interface MessagingProvider {

    String PROPERTY = "cobre.messaging.provider";
    String RABBIT = "rabbit";
    String AWS = "aws";
    String DEFAULT = RABBIT;

    String value();

    class OnProviderCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Map<String, Object> attributes =
                    metadata.getAnnotationAttributes(MessagingProvider.class.getName());
            if (attributes == null) {
                return true;
            }
            String configured = context.getEnvironment()
                    .getProperty(PROPERTY, DEFAULT)
                    .trim()
                    .toLowerCase(Locale.ROOT);

            return configured.equals(String.valueOf(attributes.get("value")));
        }
    }
}
