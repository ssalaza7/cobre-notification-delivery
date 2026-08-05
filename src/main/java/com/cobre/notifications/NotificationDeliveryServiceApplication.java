package com.cobre.notifications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Servicio de entrega de notificaciones de eventos de Cobre.
 *
 * <p>Dos capacidades sobre el mismo modelo: entregar cada evento de la plataforma al
 * webhook del cliente suscrito, con reintentos y bitacora, y exponer una API
 * self-service para que el cliente consulte y reenvie sus notificaciones.
 */
@SpringBootApplication
public class NotificationDeliveryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationDeliveryServiceApplication.class, args);
    }
}
