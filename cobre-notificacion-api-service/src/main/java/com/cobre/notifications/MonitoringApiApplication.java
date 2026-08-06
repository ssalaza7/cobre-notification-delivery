package com.cobre.notifications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API self-service.
 *
 * <p>Expone la consulta de notificaciones y el reenvio manual. Es el unico modulo con
 * superficie HTTP entrante, y por tanto el unico que necesita la cadena de seguridad, la
 * emision de tokens y el limite de peticiones.
 */
@SpringBootApplication
public class MonitoringApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitoringApiApplication.class, args);
    }
}
