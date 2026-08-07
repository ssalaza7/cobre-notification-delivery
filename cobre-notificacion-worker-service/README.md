# cobre-notificacion-worker-service

Entrega las notificaciones al webhook del cliente y aplica la política de reintentos.

Es donde vive la lógica de entrega: resolver el destino, firmar, clasificar el fallo, decidir
si se reintenta y cerrar el estado.

```
SQS  ──►  worker  ──►  webhook del cliente   (POST firmado con HMAC)
                  ──►  PostgreSQL            (estado + bitácora de intentos)
                  ──►  SQS                   (reintento con retardo, o DLQ)
```

## Qué consume y qué produce

| | |
|---|---|
| Entrada | Cola de entrega en SQS |
| Salida | POST al webhook · fila en `delivery_attempt` · estado en `notification_event` |
| Puerto HTTP | 8081, solo `/actuator` |

No expone API de negocio. El servidor existe para las sondas y las métricas.

## Cómo lee de la cola

*Long polling*: pide hasta 10 mensajes y la llamada espera hasta 20 segundos. El mensaje se
borra después de procesar, y solo transporta el identificador: el estado se relee de la base en
cada intento.

## Reintentos

Escalones: **5s · 30s · 2m · 10m · 15m**. El último coincide con el máximo que admite
`DelaySeconds` en SQS.

Cada espera lleva un componente aleatorio de hasta el 20 %.

| Respuesta del destino | Qué hace |
|---|---|
| 2xx | `completed` |
| 5xx, 408, 429, timeout, error de conexión | Reintenta con el siguiente escalón |
| 4xx (salvo 408 y 429) | `failed` sin reintentar: el contrato está roto |
| 3xx | `failed`. No se siguen redirecciones: un 302 podría reapuntar a la red interna |

Agotados los intentos, el worker envía el mensaje a la DLQ con el motivo y el evento queda
`failed`, disponible para reenvío desde la api.

## Seguridad de la entrega

Cada POST va firmado con HMAC-SHA256 sobre `timestamp + "." + cuerpo`, en las cabeceras
`X-Cobre-Timestamp` y `X-Cobre-Signature`. El instante forma parte del contenido firmado.

Antes de llamar, la URL se valida: fuera del perfil `local` se exige HTTPS y se rechazan las
direcciones que resuelvan a la red interna.

## Configuración

```yaml
cobre:
  sqs:
    delivery-queue-url: ${SQS_DELIVERY_QUEUE:...}
    dead-letter-queue-url: ${SQS_DLQ:...}
    max-messages: 10
    wait-time: 20s
  webhook:
    require-https: true
    block-internal-addresses: true
    connect-timeout: 2s
    response-timeout: 5s
    override-url: ${WEBHOOK_OVERRIDE_URL:}
  retry:
    max-attempts: 5
    delays: 5s, 30s, 2m, 10m, 15m
    jitter-ratio: 0.2
```

`WEBHOOK_OVERRIDE_URL` sustituye el destino de todas las suscripciones sin tocar la base.

## Ejecución

```bash
./gradlew :cobre-notificacion-worker-service:bootJar

SPRING_PROFILES_ACTIVE=local java -jar \
  cobre-notificacion-worker-service/build/libs/cobre-notificacion-worker-service-0.0.1-SNAPSHOT.jar
```

## Dependencia propia

El cliente HTTP reactivo. No incluye Kafka ni Spring Security.

---

Las decisiones de diseño y sus alternativas están en el [documento de diseño](../docs/01-diseno-del-sistema.md).
