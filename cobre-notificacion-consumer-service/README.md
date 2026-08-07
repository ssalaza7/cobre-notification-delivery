# cobre-notificacion-consumer-service

Consume los eventos que la plataforma publica en el bus, los persiste y encola su entrega.

No entrega nada ni expone API de negocio. Su única responsabilidad es traducir un mensaje del
bus en trabajo pendiente.

```
Kafka  ──►  consumer  ──►  DynamoDB     (guarda el evento)
                      ──►  SQS          (encola la entrega)
```

## Qué consume y qué produce

| | |
|---|---|
| Entrada | Topic `cobre.platform.events` |
| Salida | Ítem `EVENT#{id} / META` en DynamoDB + mensaje en la cola de entrega |
| Puerto HTTP | 8083, solo `/actuator` |

El servidor HTTP existe para las sondas del orquestador y para que Prometheus raspe las
métricas. No sirve tráfico de negocio.

## Cómo lee del bus

Kafka es *pull*: el consumidor mantiene una petición abierta y el broker responde en cuanto hay
datos. El offset se confirma después de persistir y encolar.

## Manejo de fallos

| Situación | Qué hace |
|---|---|
| Mensaje que no se puede deserializar | Confirma el offset y lo descarta |
| Fallo de la base o de la cola | No confirma el offset. Kafka reentrega |

Si el evento ya existe y sigue en `PENDING` —persistido pero sin mensaje en cola— se reencola.

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

Un solo `group-id` para todas las réplicas.

## Ejecución

```bash
./gradlew :cobre-notificacion-consumer-service:bootJar

SPRING_PROFILES_ACTIVE=local java -jar \
  cobre-notificacion-consumer-service/build/libs/cobre-notificacion-consumer-service-0.0.1-SNAPSHOT.jar
```

## Dependencia propia

`reactor-kafka`, con `kafka-clients` fijado a 3.9.1.

---

Las decisiones de diseño y sus alternativas están en el [documento de diseño](../docs/01-diseno-del-sistema.md).
