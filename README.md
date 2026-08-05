# notification-delivery-service

Entrega de notificaciones de eventos a webhooks de clientes, con reintentos y bitácora,
más una API self-service para consultar y reenviar.

Prueba técnica para **Cobre**. No es un sistema completo: es la base para discutir
decisiones de arquitectura, con lo esencial funcionando de punta a punta.

---

## Qué hace

**1. Entrega de notificaciones.** Consume los eventos que publica la plataforma,
confirma que el cliente tenga suscripción activa, entrega al webhook por HTTPS con
firma HMAC, reintenta con backoff exponencial y jitter cuando el destino falla, y
registra cada intento.

**2. API self-service.** Tres endpoints para que el cliente consulte sus
notificaciones, vea el detalle de una y reenvíe las que fallaron definitivamente.

```
GET  /notification_events                      → listado con filtros y paginación
GET  /notification_events/{id}                 → detalle con bitácora de intentos
POST /notification_events/{id}/replay          → reenvío de una entrega fallida
```

---

## Stack

- Java 21, Spring Boot 4.0.7, **Gradle** (wrapper incluido)
- **WebFlux** (Netty, no bloqueante) — ninguna entrega bloquea un hilo esperando al webhook
- **PostgreSQL** con R2DBC + Flyway
- **RabbitMQ** con `reactor-rabbitmq` para la cola de entrega, los retardos y la DLQ
- **Spring Security** como resource server JWT
- Micrometer → Prometheus y Datadog; logs JSON en **ECS** → Filebeat → Elasticsearch/Kibana
- JUnit 5, Mockito, `StepVerifier`, JaCoCo con gate del 90%

---

## Arquitectura: hexagonal (puertos y adaptadores)

```
domain/                                → modelo puro, cero dependencias de framework
  model/      NotificationEvent · DeliveryStatus · RetryPolicy · Subscription
              DeliveryAttempt · EventQuery · PageResult · EventVersion
  exception/  NotificationEventNotFound · ReplayNotAllowed · InvalidWebhookUrl …

application/
  port/in/    Ingest · Deliver · Query · Get · Replay
  port/out/   NotificationEventRepository · DeliveryAttemptRepository
              SubscriptionRepository · WebhookClient · DeliveryQueue · Metrics
  service/    los casos de uso; orquestan dominio y puertos

infrastructure/
  adapter/in/web/          controller REST, DTOs, errores RFC 7807
  adapter/in/messaging/    consumidores AMQP
  adapter/out/persistence/ R2DBC
  adapter/out/webhook/     WebClient + firma HMAC + validación anti-SSRF
  adapter/out/messaging/   RabbitMQ: entrega, colas de retardo, DLQ
  adapter/out/metrics/     Micrometer
  observability/           propagación de MDC en reactivo
  config/                  cableado de beans y seguridad
```

Las dependencias apuntan **siempre hacia adentro**. `domain` y `application` no importan
una sola clase de Spring; todo el cableado vive en `infrastructure/config/AppConfig`.

Consecuencia verificable: las pruebas del dominio y los casos de uso corren sin contexto
de Spring, sin base de datos y sin broker, en menos de dos segundos.

---

## Cómo correr

### 0. Prerrequisitos

- Docker corriendo
- Java 21 — si no lo tienes, Gradle lo descarga solo (toolchain configurada)
- No necesitas instalar Gradle: usa `./gradlew`

### 1. Levantar la infraestructura

```bash
docker compose up -d
```

Postgres en `5432`, RabbitMQ en `5672` y su consola en http://localhost:15672
(`guest`/`guest`).

> Si esos puertos están ocupados, son configurables:
> `PG_PORT=5433 RABBITMQ_PORT=5673 RABBITMQ_UI_PORT=15673 docker compose up -d`

### 2. Arrancar un receptor de webhooks de prueba

En otra terminal. Simula el endpoint del cliente y **verifica la firma HMAC**:

```bash
python3 scripts/webhook-receiver.py
```

Variantes útiles para la demo:

```bash
python3 scripts/webhook-receiver.py --fail-first 2   # falla 2 veces y luego acepta
python3 scripts/webhook-receiver.py --status 500     # falla siempre: agota reintentos
```

### 3. Arrancar la aplicación

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

El perfil `local` acorta los reintentos (3s · 8s · 20s · 60s) para poder verlos completos
en una demostración, y permite webhooks HTTP hacia `localhost`. En cualquier otro perfil
se exige HTTPS y se bloquean destinos internos.

Flyway crea el esquema y siembra los 10 eventos del archivo `notification_events.json`.

```bash
curl -s http://localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

### 4. Generar un token

La API exige JWT. Para la prueba se firman con HS256 y una clave local:

```bash
TOKEN=$(python3 scripts/generate-token.py CLIENT002)
```

---

## Probar la API

### Listado con filtros

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/notification_events?size=2" | python3 -m json.tool
```

```json
{
  "data": [
    {
      "event_id": "EVT008",
      "client_id": "CLIENT002",
      "event_type": "debit_purchase",
      "content": "Point of sale purchase at Store ABC for $75.25",
      "created_at": "2024-03-15T16:10:13Z",
      "delivery_status": "completed",
      "delivery_date": "2024-03-15T16:10:15Z",
      "attempts": 1,
      "replay_count": 0,
      "last_http_status": 200,
      "last_error": null
    }
  ],
  "page": 0, "size": 2, "total_elements": 3, "total_pages": 2, "has_next": true
}
```

Filtros por fecha de creación y estado de entrega:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/notification_events?delivery_status=failed&created_from=2024-03-15T00:00:00Z&created_to=2024-03-16T00:00:00Z"
```

### Aislamiento entre clientes

`EVT005` pertenece a `CLIENT003`. Con un token de `CLIENT002`:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/notification_events/EVT005
# 404
```

**404 y no 403.** Un 403 confirmaría que el recurso existe y permitiría enumerar
identificadores ajenos. Hacia afuera, "no existe" y "no es tuyo" son indistinguibles.

### Detalle con bitácora de intentos

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/notification_events/EVT003 | python3 -m json.tool
```

### Reenvío de una entrega fallida

```bash
curl -s -i -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/notification_events/EVT003/replay
# HTTP/1.1 202 Accepted
```

**202 y no 200:** el reenvío quedó encolado, no entregado. Repetir la llamada de
inmediato devuelve `409` porque la notificación ya no está en fallo definitivo.

---

## Ver el ciclo completo de entrega y reintentos

Con el receptor corriendo en modo `--fail-first 2`, publica un evento en el bus de la
plataforma:

```bash
./scripts/publish-event.sh EVT-DEMO-1 CLIENT001 credit_transfer "Transferencia por 1.500.000"
```

En el receptor:

```
[EVT-DEMO-1] intento 1 -> respondo 503 | FIRMA OK
[EVT-DEMO-1] intento 2 -> respondo 503 | FIRMA OK
[EVT-DEMO-1] intento 3 -> respondo 200 | FIRMA OK
```

Y consultando el detalle se ve el backoff real, con jitter:

```
intento 1: retryable_failure http=503   16:35:45
intento 2: retryable_failure http=503   16:35:48   (+3.4s)
intento 3: delivered        http=200    16:35:57   (+9.1s)
```

Con `--status 500` se agotan los reintentos: la notificación queda en `failed`, el
mensaje va a la DLQ y el endpoint de reenvío queda disponible.

---

## Observabilidad

### Logs estructurados en Kibana

```bash
docker compose --profile observability up -d
```

Kibana en http://localhost:5601 (índice `cobre-notifications-*`).

La aplicación escribe **JSON en formato ECS** y Filebeat lo envía. La app no conoce
Elasticsearch: si el cluster se cae, ni se entera.

Cada línea lleva `request_id`, `client_id` y `event_id` como campos indexados, así que
una consulta reconstruye el ciclo completo de una notificación:

```
17:00:37.775 [DEBUG] client=CLIENT002 hilo=rabbitmq-nio       Evento EVT-KIBANA-1 aceptado y encolado
17:00:37.914 [WARN ] client=CLIENT002 hilo=rabbitmq-nio       Entrega falló (intento 1, status 503): reintento en PT3.38S
17:00:40.960 [INFO ] client=CLIENT002 hilo=reactor-tcp-nio-2  Notificación entregada en el intento 2
```

Fíjate en la columna del hilo: la última línea corrió en un hilo distinto, segundos
después, y **conserva la correlación**. En WebFlux el MDC vive en un `ThreadLocal` y se
pierde en cada salto de hilo; se resuelve con `context-propagation` y un
`ThreadLocalAccessor` por campo. Sin eso, todos esos campos salen nulos.

**El `content` de la notificación nunca se registra**: es dato financiero del cliente.

### Métricas

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/actuator/prometheus | grep cobre_
```

```
cobre_notification_backlog{status="failed"} 3.0
cobre_notification_delivery_attempts_total{event_type="credit_transfer",outcome="DELIVERED"} 2.0
cobre_notification_delivery_seconds_bucket{...}
```

Datadog está cableado y **apagado por defecto** (`COBRE_DATADOG_ENABLED`). La vía
recomendada es que el Datadog Agent haga scraping del endpoint Prometheus: la aplicación
no necesita la API key ni tráfico saliente.

Ninguna métrica lleva `client_id` como etiqueta — con miles de clientes eso multiplica
las series de tiempo y, en Datadog, la factura.

---

## Pruebas

```bash
./gradlew clean build
```

**140 pruebas** unitarias. Cobertura **98.2% instrucciones / 97.8% líneas**; el build
falla si baja del 90%. Reporte en `build/reports/jacoco/test/html/index.html`.

Se excluyen del gate los adaptadores de persistencia y el cableado de beans: no se
pueden verificar sin base de datos real. Se cubren con la verificación manual de arriba;
el siguiente paso es Testcontainers.

El adaptador de webhooks se prueba contra un **servidor HTTP real** del JDK, no contra un
mock: lo que hay que verificar ahí es comportamiento de red —timeouts, conexiones
rechazadas, redirecciones— que un mock daría por supuesto.

---

## Documentación

| Documento | Contenido |
|---|---|
| [Diseño del sistema](docs/01-diseno-del-sistema.md) | **Task 1** — C4, secuencias, despliegue en AWS, escalabilidad, resiliencia, limitaciones |
| [Seguridad OWASP](docs/02-seguridad-owasp.md) | **Task 3** — 5 vulnerabilidades con mitigación implementada |
| [Referencia API de Cobre](docs/03-referencia-api-cobre.md) | Cómo lo hace Cobre hoy, qué se adoptó y dónde esta propuesta va más lejos |

---

## Limitaciones conocidas

Dichas antes de que las pregunten:

1. **Sin circuit breaker por cliente.** Un webhook caído horas sigue gastando 5 intentos
   por evento.
2. **Rate limit por instancia** — el contador vive en memoria. Contiene el abuso
   accidental, no el deliberado. En AWS pertenece al WAF.
3. **Sin pruebas de integración con infraestructura real.**
4. **Secretos de firma en texto plano en la base.** Deben ir cifrados con KMS.
5. **HS256 con secreto compartido.** En producción, JWKS contra el IdP de Cobre.
6. **La DLQ no tiene proceso automático** de reproceso ni alarma por profundidad.

---

## Apagar todo

```bash
# Ctrl+C donde corre bootRun y el receptor de webhooks
docker compose --profile observability down
```
