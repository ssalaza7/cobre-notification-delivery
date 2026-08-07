# cobre-notificacion-consumer-service

Consume los eventos que la plataforma publica en el bus, los persiste y encola su entrega.

No entrega nada ni expone API de negocio. Su única responsabilidad es traducir un mensaje del
bus en trabajo pendiente.

```
Kafka  ──►  consumer  ──►  PostgreSQL   (guarda el evento)
                      ──►  SQS          (encola la entrega)
```

## Qué consume y qué produce

| | |
|---|---|
| Entrada | Topic `cobre.platform.events` |
| Salida | Fila en `notification_event` + mensaje en la cola de entrega |
| Puerto HTTP | 8083, solo `/actuator` |

El servidor HTTP existe para las sondas del orquestador y para que Prometheus raspe las
métricas. No sirve tráfico de negocio.

## Cómo lee del bus

Kafka no empuja mensajes: el consumidor mantiene una petición abierta y el broker responde en
cuanto hay datos. El offset se confirma **después** de persistir y encolar, nunca al recibir.

## Manejo de fallos

La distinción es deliberada y corrige un defecto que perdía eventos en silencio:

| Situación | Qué hace |
|---|---|
| Mensaje que no se puede deserializar | Confirma el offset y lo descarta. Reintentarlo bloquearía la partición sin posibilidad de éxito |
| Fallo de la base o de la cola | **No** confirma el offset. Kafka reentrega |

La reentrega por sí sola no basta: si la fila ya se escribió en el intento anterior,
`insertIfAbsent` devuelve `false`. Por eso, cuando el evento sigue en `PENDING` —persistido
pero sin mensaje en cola— se reencola. Solo en `PENDING`: en `RETRYING` ya hay un mensaje
esperando con su retardo, y reencolarlo produciría un intento de más.

## Configuración

```yaml
cobre:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    topic: ${KAFKA_TOPIC:cobre.platform.events}
    group-id: ${KAFKA_GROUP:notification-delivery-service}
  sqs:
    delivery-queue-url: ${SQS_DELIVERY_QUEUE:...}
```

Un solo `group-id` para todas las réplicas: se reparten las particiones en lugar de procesar
cada una el mismo evento.

## Ejecución

```bash
./gradlew :cobre-notificacion-consumer-service:bootJar

SPRING_PROFILES_ACTIVE=local java -jar \
  cobre-notificacion-consumer-service/build/libs/cobre-notificacion-consumer-service-0.0.1-SNAPSHOT.jar
```

## Dependencia propia

`reactor-kafka`, con `kafka-clients` fijado a 3.9.1. Boot 4 trae la 4.x, que elimina
constructores contra los que `reactor-kafka` está compilado. La fijación vive solo en este
módulo: los otros dos no cargan Kafka.
