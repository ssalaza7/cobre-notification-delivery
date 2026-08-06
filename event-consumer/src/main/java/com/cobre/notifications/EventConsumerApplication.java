package com.cobre.notifications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Consumidor del bus de la plataforma.
 *
 * <p>Lee los eventos que publican los demas servicios, los persiste de forma idempotente
 * y encola la entrega. No conoce el cliente de webhooks ni la cadena de seguridad de la
 * API: esas dependencias no estan en su classpath.
 */
@SpringBootApplication
public class EventConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventConsumerApplication.class, args);
    }
}
