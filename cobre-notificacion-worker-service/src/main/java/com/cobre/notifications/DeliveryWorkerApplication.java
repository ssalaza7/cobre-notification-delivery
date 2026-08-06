package com.cobre.notifications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Worker de entrega.
 *
 * <p>Consume la cola de trabajo, entrega al webhook del cliente y aplica la politica de
 * reintentos. Es el unico modulo que hace peticiones salientes a terceros, y por eso el
 * unico que necesita el cliente HTTP, la firma HMAC y la validacion anti-SSRF.
 */
@SpringBootApplication
public class DeliveryWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeliveryWorkerApplication.class, args);
    }
}
