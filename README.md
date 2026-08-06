# notification-delivery-service

Servicio de entrega de notificaciones de eventos a webhooks de clientes, con reintentos,
firma HMAC y bitácora de intentos. Expone una API self-service de consulta y reenvío.

Prueba técnica para Cobre.

```
GET  /notification_events              listado con filtros y paginación
GET  /notification_events/{id}         detalle con la bitácora de cada intento
POST /notification_events/{id}/replay  reenvío de una entrega fallida
```

---

## 1. Arquitectura

```mermaid
flowchart LR
    SVC["Servicios de la plataforma<br/>pagos · transferencias · saldos"]
    K[("Kafka<br/>cobre.platform.events")]
    W["<b>Worker</b><br/>entrega y reintenta"]
    API["<b>API</b><br/>self-service"]
    Q[("SQS<br/>cola de entrega")]
    DLQ[("SQS<br/>DLQ")]
    DB[("PostgreSQL<br/>eventos + bitácora")]
    CLI["Webhook del cliente"]
    USR["Cliente"]

    SVC -->|publica| K
    K -->|consume| W
    W <-->|encola · reintenta| Q
    Q -.->|reintentos agotados| DLQ
    W -->|POST firmado HMAC| CLI
    W --- DB
    API --- DB
    USR -->|consulta · reenvía| API
    API -.->|encola reenvío| Q
```

### Worker y API son despliegues independientes

La carga del worker la determina el volumen de eventos de la plataforma; la de la API, el
uso que hagan las personas. Son magnitudes distintas y evolucionan por separado, de modo que
escalar una no debe obligar a provisionar réplicas de la otra. La misma imagen arranca en uno
u otro rol según la variable `COBRE_ROLE`.

### Kafka como bus, SQS como cola de trabajo

Kafka transporta los eventos que publica la plataforma. No elimina el mensaje al leerlo, por
lo que varios servicios pueden consumir el mismo evento de forma independiente.

SQS contiene el trabajo pendiente de entrega. Se emplea para esta etapa por dos razones: el
paralelismo en Kafka está limitado por el número de particiones, mientras que en SQS cada
consumidor toma el siguiente mensaje disponible; y SQS ofrece de forma nativa el retardo por
mensaje que implementa el backoff (`DelaySeconds`) y la cola de mensajes no entregados (DLQ).

En entorno local se usan Redpanda y ElasticMQ, que implementan los mismos protocolos. El
código es idéntico al que se ejecutaría contra Confluent Cloud y AWS; solo cambian las
direcciones de conexión.

---

## 2. Arquitectura hexagonal

```
domain/          modelo puro, sin dependencias de framework
application/     port/in · port/out · casos de uso
infrastructure/  adaptadores: REST · Kafka · SQS · R2DBC · WebClient · Micrometer
```

Las dependencias apuntan siempre hacia el interior. Los paquetes `domain` y `application` no
importan ninguna clase de Spring; el cableado reside en `infrastructure/config`.

La consecuencia verificable es que las pruebas del dominio y de los casos de uso se ejecutan
sin contexto de Spring, sin base de datos y sin broker.

El efecto práctico quedó demostrado durante el desarrollo: la sustitución de RabbitMQ por
Kafka y SQS no requirió modificar el dominio ni los casos de uso, solo los adaptadores.

---

## 3. Escenarios

### 3.1 Entrega exitosa

```mermaid
sequenceDiagram
    participant P as Plataforma
    participant K as Kafka
    participant W as Worker
    participant DB as PostgreSQL
    participant Q as SQS
    participant C as Webhook del cliente

    P->>K: publica evento
    K->>W: consume
    W->>DB: guarda (idempotente por event_id)
    W->>Q: encola la entrega
    Note over W,K: confirma el offset
    Q->>W: entrega el mensaje
    W->>DB: verifica suscripción activa
    W->>C: POST + firma HMAC
    C-->>W: 200
    W->>DB: completed · registra el intento
    W->>Q: borra el mensaje (confirmación)
```

El offset de Kafka se confirma después de persistir el evento, y el mensaje de SQS se elimina
después de completar la entrega. Si el proceso termina de forma abrupta en un punto
intermedio, el evento se reentrega.

La garantía es, por tanto, de entrega **al menos una vez**: una notificación puede llegar
duplicada al cliente. La alternativa —confirmar antes de procesar— produciría pérdida
silenciosa de eventos, que en notificaciones de pagos tiene mayor impacto que un duplicado.
El duplicado se acota por dos vías: la ingesta es idempotente por `event_id`, y cada entrega
incluye la cabecera `X-Cobre-Event-Id` para que el receptor descarte repeticiones.

### 3.2 Entrega recuperada tras reintentos

```mermaid
sequenceDiagram
    participant Q as SQS
    participant W as Worker
    participant C as Webhook del cliente

    Q->>W: intento 1
    W->>C: POST
    C-->>W: 503
    W->>Q: reencola con DelaySeconds ≈ 3s
    Note over W: retrying · intento registrado

    Q->>W: intento 2 (3,2 s después)
    W->>C: POST
    C-->>W: 200
    W->>Q: borra el mensaje
    Note over W: completed en el intento 2
```

Los escalones de espera son 5s, 30s, 2m, 10m y 15m. El último coincide con el máximo que
admite `DelaySeconds` en SQS.

Cada espera incorpora un componente aleatorio (*jitter*) de hasta el 20 %. Sin él, todas las
notificaciones acumuladas durante la caída de un destino se reintentarían en el mismo
instante, generando un pico de carga sobre un sistema que acaba de restablecerse.

### 3.3 Reintentos agotados

```mermaid
sequenceDiagram
    participant Q as SQS
    participant W as Worker
    participant C as Webhook del cliente
    participant D as DLQ

    loop hasta agotar los intentos
        Q->>W: intento n
        W->>C: POST
        C-->>W: 503
    end
    W->>D: deriva el mensaje a la DLQ
    Note over W: failed · habilitado para reenvío manual
```

El evento queda en estado `failed` con su bitácora completa y el mensaje se deriva a la DLQ
(*dead letter queue*) para inspección. Ninguna información se descarta.

Las respuestas 4xx no llegan a este escenario: se clasifican como fallo permanente y no se
reintentan, dado que la repetición produciría el mismo resultado.

### 3.4 Reenvío manual

```mermaid
sequenceDiagram
    participant U as Cliente
    participant API
    participant DB as PostgreSQL
    participant Q as SQS
    participant W as Worker

    U->>API: POST /oauth/token
    API-->>U: access_token
    U->>API: POST /notification_events/{id}/replay
    API->>DB: valida propiedad y estado failed
    API->>DB: reinicia el ciclo · replay_count + 1
    API->>Q: encola
    API-->>U: 202 Accepted
    Q->>W: el worker entrega con el flujo habitual
```

La respuesta es 202 y no 200: la solicitud queda encolada, no entregada. La bitácora es de
solo adición, por lo que el reenvío conserva los intentos del ciclo anterior.

---

## 4. Evidencia

### Traza de una notificación en los logs

Evento que falla en el primer intento y se entrega en el segundo:

```
17:24:34.167            DEBUG        Evento EVT-RECUPERA-README del cliente CLIENT001 aceptado y encolado
17:24:34.181  delivery  INFO   503   Entrega saliente -> 503 en 8ms
17:24:34.193            WARN         Entrega de EVT-RECUPERA-README fallo (intento 1, status 503): reintento en PT3.234S
17:24:37.226  delivery  INFO   200   Entrega saliente -> 200 en 3ms
17:24:37.240            INFO         Notificacion EVT-RECUPERA-README entregada al cliente CLIENT001 en el intento 2
```

`PT3.234S` es la representación ISO-8601 de 3,234 segundos; el decimal corresponde al jitter.

Cada línea incluye `event_id`, `client_id` y `request_id` como campos indexados, lo que
permite reconstruir el ciclo completo con una sola consulta. El campo `content` de la
notificación no se registra, por tratarse de información financiera del cliente.

### Logs en Kibana

![Logs en Kibana](docs/img/kibana-logs.png)

### Métricas en Grafana

![Tablero de Grafana](docs/img/grafana-tablero.png)

Los indicadores principales son *reintentos exitosos* (entregas recuperadas por el backoff,
que cuantifican el valor de la estrategia de reintentos), *reintentos agotados* (casos que
requieren intervención) y *clientes con entregas fallando* (identificación del cliente
afectado para notificar a su equipo).

### Bitácora devuelta por la API

```json
{
  "event_id": "EVT-RECUPERA-README",
  "delivery_status": "completed",
  "attempts": 2,
  "delivery_attempts": [
    { "attempt_number": 2, "outcome": "delivered",         "http_status": 200, "duration_ms": 3 },
    { "attempt_number": 1, "outcome": "retryable_failure", "http_status": 503, "duration_ms": 9 }
  ]
}
```

### Pruebas

196 pruebas, sin fallos. Cobertura del 94,5 % de instrucciones y 94,6 % de líneas. El build
falla si la cobertura desciende del 90 %.

---

## 5. Ejecución

Requisitos: Docker y Java 21. No es necesario instalar Gradle.

```bash
# 1. Secreto de firma de tokens. La aplicación no arranca sin él.
cp .env.example .env
openssl rand -base64 48          # asignar el resultado a JWT_SECRET
set -a; source .env; set +a

# 2. PostgreSQL, Kafka (Redpanda) y SQS (ElasticMQ)
docker compose up -d

# 3. Receptor de webhooks de prueba: simula el sistema del cliente y verifica la firma
python3 scripts/webhook-receiver.py

# 4. Aplicación
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun

# 5. Publicación de un evento
./scripts/publish-event.sh EVT-DEMO-1 CLIENT001 credit_transfer "Transferencia por 1.500.000"
```

El perfil `local` reduce los escalones de reintento y admite destinos HTTP en `localhost`.
En cualquier otro perfil se exige HTTPS y se bloquean las direcciones internas.

Las consolas de observabilidad se levantan con
`docker compose --profile observability up -d`.

---

## 6. Documentación

| Documento | Contenido |
|---|---|
| [Diseño del sistema](docs/01-diseno-del-sistema.md) | Task 1 — escalabilidad, resiliencia, despliegue |
| [Seguridad OWASP](docs/02-seguridad-owasp.md) | Task 3 — vulnerabilidades identificadas y mitigaciones |
| [Guía de uso](docs/03-guia-de-uso.md) | Registro de webhooks, tokens, Postman, consolas, receptor de pruebas |

---

## 7. Limitaciones conocidas

1. **No hay circuit breaker por cliente.** Un destino caído durante horas continúa
   consumiendo intentos en cada evento.
2. **El límite de tasa es por instancia**, no global. Contiene el abuso accidental pero no el
   deliberado. En un despliegue en AWS este control corresponde al WAF.
3. **No hay pruebas de integración con infraestructura real.** Los adaptadores de
   persistencia y el cableado de beans quedan fuera del umbral de cobertura. El paso
   pendiente es Testcontainers.
4. **El secreto de firma del webhook se almacena en texto plano.** No admite hash porque debe
   usarse para calcular el HMAC de cada entrega. La mitigación es cifrarlo con KMS.
5. **La DLQ no dispone de reproceso automático** ni de alarma por profundidad de cola.

---

```bash
docker compose --profile observability down
```
