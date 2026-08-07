# cobre-notification-delivery

Entrega notificaciones de eventos a los webhooks de los clientes, con reintentos, firma
HMAC y bitácora de cada intento. Expone además una API self-service de consulta y reenvío.

Prueba técnica para Cobre.

```
GET  /notification_events              listado con filtros y paginación
GET  /notification_events/{id}         detalle con la bitácora de cada intento
POST /notification_events/{id}/replay  reenvío de una entrega fallida

POST /subscriptions                    registra el webhook y devuelve su secreto de firma
GET  /subscriptions                    las suscripciones del cliente
```

---

## 1. Arquitectura

```mermaid
flowchart LR
    SVC["Servicios de la plataforma<br/>pagos · transferencias · saldos"]
    K[("Kafka<br/>cobre.platform.events")]
    Q[("SQS<br/>cola de entrega")]
    DLQ[("SQS<br/>DLQ")]
    DB[("PostgreSQL<br/>eventos + bitácora")]
    CLI["Webhook del cliente"]
    USR["Cliente"]

    subgraph svc["Entrega de notificaciones"]
        CON["<b>consumer</b><br/>ingesta y encola"]
        W["<b>worker</b><br/>entrega y reintenta"]
        API["<b>api</b><br/>consulta y reenvío"]
    end

    SVC -->|publica| K
    K -->|consume| CON
    CON -->|persiste| DB
    CON -->|encola| Q
    Q -->|toma la orden| W
    W -->|consulta suscripción<br/>registra intento| DB
    W -->|POST firmado HMAC| CLI
    W -->|reencola con retardo| Q
    W -->|reintentos agotados| DLQ
    Q -.->|mensaje no confirmado| DLQ
    USR -->|consulta · reenvía| API
    API -->|consulta| DB
    API -->|encola reenvío| Q

    style CON fill:#1f6feb,color:#fff
    style W fill:#1f6feb,color:#fff
    style API fill:#1f6feb,color:#fff
```

La flecha indica la dirección del dato. La línea punteada marca el único flujo que ningún
componente invoca: cuando un mensaje no se confirma tras varias entregas —por ejemplo, uno
corrupto que nunca llega a procesarse— SQS lo mueve a la DLQ por su *redrive policy*.

Los reintentos agotados son distintos: ahí el worker envía el mensaje a la DLQ de forma
explícita, con el motivo del descarte.

### Tres componentes desplegables

| Componente | Responsabilidad | Señal de escalado |
|---|---|---|
| **consumer** | Ingesta desde el bus y encola la entrega | Retraso del consumidor |
| **worker** | Entrega al webhook y aplica los reintentos | Profundidad de la cola |
| **api** | Consulta y reenvío manual | Peticiones por segundo |

Se despliegan por separado porque sus cargas son de naturaleza distinta: el worker sigue el
ritmo de la plataforma y la API el de las personas. Escalar una no debe obligar a provisionar
réplicas de la otra. Además, la saturación de cada una tiene consecuencias diferentes —
consultas degradadas frente a notificaciones sin entregar.

Cada componente es un artefacto propio y carga solo las dependencias que usa. Verificable
sobre los jars construidos:

| Componente | Dependencia propia | Lo que no incluye |
|---|---|---|
| `consumer` | `reactor-kafka`, `kafka-clients` | Spring Security |
| `worker` | cliente HTTP reactivo, firma HMAC | Kafka, Spring Security |
| `api` | Spring Security, resource server JWT | Kafka |

Qué adaptadores se activan lo determina el classpath de cada artefacto, no una condición
evaluada al arrancar.

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

![Arquitectura hexagonal](docs/img/arquitectura-hexagonal.svg)

Tres capas concéntricas y una sola regla: **las dependencias apuntan siempre hacia adentro.**

**Dominio.** El modelo del negocio: qué es una notificación, en qué estados puede estar, cuándo
se puede reenviar, cuánto se espera entre reintentos. Y los puertos, que son las interfaces con
las que le pide cosas al exterior.

**Aplicación.** Los casos de uso. Orquestan el dominio y los puertos, y no saben qué hay al otro
lado: `DeliverNotificationEventService` pide «entrega esto» sin enterarse de que debajo hay un
`WebClient`.

**Infraestructura.** Los adaptadores, que son lo único que conoce la tecnología. De entrada,
los que traen trabajo: el consumidor de Kafka, el de la cola SQS y el controlador REST. De
salida, los que implementan los puertos: R2DBC contra PostgreSQL, el cliente HTTP hacia el
webhook del cliente, el productor de SQS y Micrometer.

### Por qué importa aquí

Lo que se gana es **poder cambiar la tecnología sin tocar el negocio**. Sustituir PostgreSQL por
un almacén clave-valor, o SQS por otra cola, afecta a un adaptador y a nadie más.

Y no es una promesa: durante el desarrollo se cambió el sistema de mensajería completo —de un
broker AMQP a Kafka más SQS— sin modificar una línea del dominio ni de los casos de uso.

### Cómo se verifica que la regla se cumple

Los paquetes `domain` y `application` no importan ninguna clase de Spring, y eso no depende de
la disciplina de nadie: lo comprueba `DominioSinFrameworkTest`, que lee los fuentes y **falla el
build** si aparece un import de Spring, R2DBC, Kafka, el SDK de AWS o Jackson.

La consecuencia práctica es que las pruebas del dominio y de los casos de uso corren sin
contexto de Spring, sin base de datos y sin broker.

---

## 3. Escenarios

### 3.1 Entrega exitosa

```mermaid
sequenceDiagram
    participant P as Plataforma
    participant K as Kafka
    participant CON as consumer
    participant DB as PostgreSQL
    participant Q as SQS
    participant W as worker
    participant C as Webhook del cliente

    P->>K: publica evento
    CON->>K: pide eventos (poll)
    K-->>CON: evento
    CON->>DB: guarda (idempotente por event_id)
    CON->>Q: encola la entrega
    Note over CON,K: confirma el offset
    W->>Q: pide mensajes (espera hasta 20s)
    Q-->>W: orden de entrega
    W->>DB: verifica suscripción activa
    W->>C: POST + firma HMAC
    C-->>W: 200
    W->>DB: completed · registra el intento
    W->>Q: borra el mensaje (confirmación)
```

Ni Kafka ni SQS empujan mensajes: el consumidor y el worker preguntan, y la llamada se queda
esperando hasta que haya algo o venza el tiempo. Por eso las flechas de petición salen de los
componentes, no de los brokers.

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
    participant W as worker
    participant C as Webhook del cliente

    W->>Q: pide mensajes
    Q-->>W: orden de entrega
    W->>C: POST
    C-->>W: 503
    W->>Q: reencola con DelaySeconds ≈ 3s
    Note over W,Q: retrying · el mensaje queda oculto 3 s

    W->>Q: pide mensajes
    Q-->>W: la misma orden, ya visible
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
    participant W as worker
    participant C as Webhook del cliente
    participant D as DLQ

    loop hasta agotar los intentos
        W->>Q: pide mensajes
        Q-->>W: orden de entrega
        W->>C: POST
        C-->>W: 503
        W->>Q: reencola con retardo
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
    participant API as api
    participant DB as PostgreSQL
    participant Q as SQS
    participant W as worker

    U->>API: POST /oauth/token
    API-->>U: access_token
    U->>API: POST /notification_events/{id}/replay
    API->>DB: valida propiedad y estado failed
    API->>DB: reinicia el ciclo · replay_count + 1
    API->>Q: encola
    API-->>U: 202 Accepted
    W->>Q: pide mensajes
    Q-->>W: la orden del reenvío, sin camino aparte
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

### Consulta de los logs en Kibana

Los logs se indexan en Elasticsearch y se consultan desde Kibana con cinco vistas
versionadas en el repositorio: todo el tráfico, entregas, llamadas a la API, solo errores y
traza de un evento. Se cargan con `./scripts/kibana-import.sh`.

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

192 pruebas, sin fallos. Cobertura agregada de los cinco módulos: 94,3 % de instrucciones
y 94,6 % de líneas. El build falla si desciende del 90 %.

---

## 5. Ejecución

Requisitos: Docker. Nada más — ni Java ni Gradle instalados.

```bash
# 1. Secreto de firma de tokens. Ningún módulo arranca sin él.
cp .env.example .env
openssl rand -base64 48          # asignar el resultado a JWT_SECRET
set -a; source .env; set +a

# 2. Todo el entorno: infraestructura, observabilidad y los tres servicios
docker compose --profile apps --profile observability up -d --build
```

La primera vez tarda unos minutos construyendo las tres imágenes. Después, segundos.

```bash
docker compose --profile apps --profile observability ps      # qué está arriba
```

### Probar

Importar [la colección de Postman](postman/) y ejecutarla de arriba abajo. Crea su propio
destino en webhook.site, registra el webhook, publica eventos y consulta el resultado. No hay
que preparar nada.

```bash
npx newman run postman/cobre-notification-delivery.postman_collection.json
```

### Ver qué pasa

| Dónde | URL | Qué se ve |
|---|---|---|
| **Grafana** | http://localhost:3000 | Entregas, reintentos, latencia, clientes fallando |
| **Kibana** | http://localhost:5601 | La traza de cada notificación. Vistas: `./scripts/kibana-import.sh` |
| **Prometheus** | http://localhost:9091 | Las métricas en crudo |
| **Kafka** | http://localhost:8085 | El topic, sus mensajes y el grupo de consumo |
| **SQS** | http://localhost:9325 | La cola de entrega y la DLQ, con su profundidad |

### Apagar

```bash
docker compose --profile apps --profile observability down
```

Añadiendo `-v` borra también los datos de PostgreSQL.

### Puertos

API 8080 · worker 8081 · consumer 8083 · Grafana 3000 · Kibana 5601 · Prometheus 9091
Kafka 8085 · SQS 9325

El perfil `local` acorta los escalones de reintento para poder verlos completos y admite
destinos HTTP. En cualquier otro perfil se exige HTTPS y se bloquean las direcciones internas.

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
