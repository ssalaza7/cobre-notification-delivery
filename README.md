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

### 0. Prerrequisitos y secretos

- Docker corriendo
- Java 21 — si no lo tienes, Gradle lo descarga solo (toolchain configurada)
- No necesitas instalar Gradle: usa `./gradlew`

**No hay secretos escritos en el código.** La clave de firma de tokens no tiene valor
por defecto y la aplicación **no arranca sin ella**: un secreto commiteado queda en el
historial de git para siempre, aunque después se borre.

```bash
cp .env.example .env
openssl rand -base64 48        # pega el resultado en JWT_SECRET dentro de .env
set -a; source .env; set +a
```

`.env` está en `.gitignore`; lo versionado es solo la plantilla. En AWS esa variable se
inyecta desde **Secrets Manager**, que permite rotarla sin redesplegar y deja registro
de cada acceso en CloudTrail.

> Las contraseñas de Postgres y RabbitMQ sí tienen valor por defecto, y es deliberado:
> son contenedores locales desechables que no dan acceso a nada. Tratarlas como secretos
> sería teatro; en entornos reales vienen del gestor igual que la clave de firma.

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

### 4. Obtener un token

La API exige un token. Se obtiene con el flujo `client_credentials` de OAuth2, contra
la propia API:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/oauth/token \
  -H 'Content-Type: application/json' \
  -d '{"grant_type":"client_credentials","client_id":"CLIENT002","client_secret":"demo-secret-client002"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
```

```json
{
  "access_token": "eyJhbGciOiJIUzI1NiJ9...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "notifications:read notifications:replay notifications:monitor"
}
```

Credenciales sembradas para la demostración:

| client_id | client_secret | scopes |
|---|---|---|
| `CLIENT001` | `demo-secret-client001` | read · replay · monitor |
| `CLIENT002` | `demo-secret-client002` | read · replay · monitor |
| `CLIENT003` | `demo-secret-client003` | **solo read** |

`CLIENT003` no tiene permiso de reenvío a propósito: sirve para demostrar que consultar
y reenviar son autorizaciones distintas.

Es `POST` y no `GET` deliberadamente: un `GET` llevaría el secreto en la URL, y las URLs
terminan en los logs del balanceador, en el historial y en la cabecera `Referer`.

---

## Colección de Postman

En [postman/](postman/) hay una colección con **21 peticiones en orden de ejecución**,
cada una con sus aserciones. Se importa y se corre entera con el Collection Runner.

El orden cuenta una historia: obtener token → consultar → filtrar → ver detalle →
comprobar el aislamiento entre clientes → reenviar → y los casos de seguridad.

| # | Qué demuestra |
|---|---|
| 01–02 | Salud pública y emisión de token |
| 03 | Sin token → `401` |
| 04–07 | Listado, paginación y los filtros por estado y por fecha |
| 08–09 | Estado inválido y página desmedida → `400` |
| 10 | Detalle con la bitácora completa de intentos |
| 11 | Notificación de otro cliente → `404`, no `403` |
| 12–14 | Reenvío `202`, repetición `409`, y el ciclo registrado |
| 15–16 | Token de solo lectura → reenviar da `403` |
| 17–18 | Secreto incorrecto y cliente inexistente → **el mismo error** |
| 19 | `grant_type` no soportado → `400` |
| 20–21 | Métricas protegidas por scope |

Las peticiones encadenan variables: el token se guarda solo, y el identificador de una
notificación fallida se captura del listado filtrado para usarlo en el detalle y el
reenvío. Si cambiaste de puerto, ajusta la variable `base_url`.

> Los pasos 12 y 13 necesitan que `CLIENT002` tenga una notificación en `failed`. En una
> base recién sembrada es `EVT003`. Si ya la reenviaste y quedó entregada, vuelve a
> sembrar con `docker compose down -v && docker compose up -d`.

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

## Guion para la demostración en vivo

El enunciado entrega la URL destino el mismo día de la presentación. Este es el
recorrido, ensayado de punta a punta.

### Antes de entrar

```bash
cp .env.example .env && openssl rand -base64 48   # pega el valor en JWT_SECRET
set -a; source .env; set +a
docker compose up -d
./gradlew bootJar                                  # compila una sola vez, antes de entrar
```

El perfil `demo` existe para esto: **exige HTTPS y bloquea destinos internos**, como en
producción, pero con escalones de reintento de 3s · 8s · 20s · 60s, que se pueden
mostrar completos mientras se explican. Ni `local` ni el perfil por defecto sirven:
`local` desactiva la exigencia de HTTPS, y por defecto el último escalón es de 30
minutos.

### Cuando te den la URL

El destino es una **variable de entorno**. Es configuración, y como tal no debería
vivir en la base de datos ni cambiarse con un `UPDATE` en vivo:

```bash
SPRING_PROFILES_ACTIVE=demo \
WEBHOOK_OVERRIDE_URL=https://el-destino-que-me-dieron/webhook \
java -jar build/libs/notification-delivery-service-0.0.1-SNAPSHOT.jar
```

**Arranca en menos de 3 segundos.** Por eso importa haber compilado antes: `./gradlew
bootRun` tarda casi un minuto, pero eso es Gradle compilando, no la aplicación
levantando. Con el jar ya construido, cambiar de destino es reiniciar y seguir hablando.

`WEBHOOK_OVERRIDE_URL` tiene precedencia sobre lo que haya en la tabla `subscription`,
así que no hay que tocar datos para redirigir la demostración.

> Alternativa sin reiniciar: `./scripts/set-webhook-url.sh <url>` cambia el destino en
> la base y aplica desde la siguiente notificación. Sirve si te dan una segunda URL a
> mitad de la demostración, pero la variable de entorno es la vía limpia.

### Disparar y mostrar

```bash
./scripts/publish-event.sh EVT-DEMO-1 CLIENT001 credit_transfer "Transferencia por 1.500.000"
```

Y consultar el resultado con la API:

```
estado   : completed
destino  : https://el-destino-que-me-dieron/webhook
  intento 1: delivered http=200 1237ms
```

### Si su URL no responde

No es un problema, es la otra mitad de la demostración: apunta a tu propio receptor con
`--fail-first 2` y muestra el ciclo de reintentos con backoff y jitter. El mecanismo es
el mismo; lo único que cambia es el destino.

---

## Entregar a una URL externa (modo estricto)

El destino de todas las suscripciones se puede sustituir con una variable de entorno,
sin tocar la base de datos:

```bash
WEBHOOK_OVERRIDE_URL=https://el-destino-que-me-den/webhook ./gradlew bootRun
```

Sin el perfil `local`, la validación va en modo estricto: **se exige HTTPS** y se
rechazan destinos que resuelvan a la red interna.

**Verificado contra un endpoint HTTPS público real:**

```
estado   : completed
destino  : https://postman-echo.com/post
intentos : 1
  intento 1: delivered http=200 1432ms
```

Y el control de seguridad no es decorativo. Con el mismo destino en `http://`:

```
estado  : failed
error   : El webhook debe usar HTTPS; se recibio esquema 'http'
intentos: 1
```

Un solo intento: una URL inválida es un fallo permanente y no gasta reintentos, porque
insistir no la va a arreglar.

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

---

## Limitaciones conocidas

Dichas antes de que las pregunten:

1. **Sin circuit breaker por cliente.** Un webhook caído horas sigue gastando 5 intentos
   por evento.
2. **Rate limit por instancia** — el contador vive en memoria. Contiene el abuso
   accidental, no el deliberado. En AWS pertenece al WAF.
3. **Sin pruebas de integración con infraestructura real.**
4. **Secretos de firma en texto plano en la base.** Deben ir cifrados con KMS.
5. **No hay emisor de tokens.** El servicio valida pero no emite; los tokens de prueba se
   firman con un script local. En producción, un proveedor OIDC y validación por JWKS.
6. **La DLQ no tiene proceso automático** de reproceso ni alarma por profundidad.

---

## Apagar todo

```bash
# Ctrl+C donde corre bootRun y el receptor de webhooks
docker compose --profile observability down
```
