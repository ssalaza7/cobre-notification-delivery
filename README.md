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
- **Kafka** como bus de eventos y **SQS** como cola de trabajo, reintentos y DLQ
  (en local, Redpanda y ElasticMQ: mismos protocolos, sin cuenta de AWS)
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
  adapter/in/messaging/    consumidor de Kafka y de la cola SQS
  adapter/out/persistence/ R2DBC
  adapter/out/webhook/     WebClient + firma HMAC + validación anti-SSRF
  adapter/out/messaging/   SQS: cola de entrega con retardo nativo y DLQ
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

> Las contraseñas de Postgres y las credenciales de los emuladores sí tienen valor por
> defecto, y es deliberado:
> son contenedores locales desechables que no dan acceso a nada. Tratarlas como secretos
> sería teatro; en entornos reales vienen del gestor igual que la clave de firma.

### 1. Levantar la infraestructura

```bash
docker compose up -d
```

Levanta Postgres, **Kafka** (Redpanda en `9092`, con proxy HTTP en `8082`) y **SQS**
(ElasticMQ en `9324`).

Redpanda y ElasticMQ hablan los mismos protocolos que Kafka y SQS reales: el código es
idéntico al que correría contra Confluent Cloud y AWS, solo cambian las direcciones.

> Si esos puertos están ocupados, son configurables:
> `PG_PORT=5433 KAFKA_PORT=9093 SQS_PORT=9325 docker compose up -d`

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

El perfil `local` acorta los reintentos (3s · 6s · 10s) para poder verlos completos
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

En [postman/](postman/), organizada en dos carpetas.

### 1 · Flujo exitoso — 1 petición

Publicar un evento en el tópico. **Es lo único que se hace**: el servicio es un consumidor
y el resto ocurre solo. Lo que se demuestra se ve en la terminal del receptor:

```
[EVT-DEMO-...] intento 1 -> respondo 200 | FIRMA OK
```

### 2 · Flujo con reintento — 1 petición

Publicar un evento cuyo destino rechaza. **Los reintentos son autónomos**, aparecen solos:

```
[EVT-FALLA-...] intento 1 -> respondo 503 | FIRMA OK
[EVT-FALLA-...] intento 2 -> respondo 503 | FIRMA OK    (+3.5s)
[EVT-FALLA-...] intento 3 -> respondo 503 | FIRMA OK    (+6.8s)
```

Esperas crecientes y no redondas: backoff exponencial con jitter. Agotados los tres, la
notificación queda en `failed`.

### 3 · API self-service — 5 peticiones

Lo que un cliente ejecuta a diario. El detalle y el reenvío apuntan al evento del flujo 2,
así que cierran esa historia:

```
detalle  -> estado=failed intentos=3
reenviar -> 202
detalle  -> estado=completed ciclos=2 intentos_registrados=4
```

Los 4 intentos registrados prueban que la bitácora es append-only: el reenvío no borra la
historia del ciclo anterior.

---

Las peticiones encadenan variables: el token se guarda al obtenerlo y el identificador de
una notificación fallida se captura del listado filtrado. Si cambiaste de puerto, ajusta
`base_url`.

> Los pasos de reenvío necesitan que `CLIENT002` tenga una notificación en `failed`. En
> una base recién sembrada es `EVT003`. Si ya la reenviaste, vuelve a sembrar con
> `docker compose down -v && docker compose up -d`.

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

### Cómo encajan las piezas

**Tu aplicación no envía métricas a nadie.** Solo las publica en `/actuator/prometheus`,
que es una foto del instante: cuántas entregas van, cuánto tardó la última.

Pero un tablero necesita **historia**, no una foto. Ahí entra Prometheus:

```
Prometheus  ──GET /actuator/prometheus──>  la aplicación
            (cada 5 segundos, y guarda)
```

Prometheus **consulta el endpoint periódicamente y guarda cada lectura**. Nadie le envía
nada: él va y pregunta. Es como un lector de medidor que pasa cada rato a anotar la
cifra, en vez de que el medidor lo llame. En inglés a eso se le dice *scraping*.

Grafana no habla con la aplicación: le pregunta a Prometheus, que es quien tiene el
histórico.

```
la aplicación  →  Prometheus  →  Grafana
   (publica)      (consulta y     (dibuja)
                   almacena)
```

Con los logs pasa lo mismo pero al revés en el último tramo: la aplicación escribe JSON,
**Filebeat lo lee y lo envía** a Elasticsearch, y Kibana consulta ahí.

```
la aplicación  →  Filebeat  →  Elasticsearch  →  Kibana
   (escribe)      (lee y        (indexa)         (consulta)
                   envía)
```

**En los dos casos la aplicación no conoce el destino final.** Por eso cambiar Prometheus
por Datadog, o Elasticsearch por otra cosa, no toca una línea de código: en AWS el agente
de Datadog consulta exactamente el mismo endpoint y guarda la misma serie.

### Consolas para la demostración

```bash
docker compose --profile observability up -d
```

| Consola | URL | Qué mostrar |
|---|---|---|
| **Grafana** | http://localhost:3000 | Tablero *Entrega de notificaciones* (ver abajo) |
| **Kibana** | http://localhost:5602 | La traza completa de una notificación, filtrando por `event_id` o `client_id` |
| **SQS** | http://localhost:9324 | La cola de entrega y la cola muerta |
| **Prometheus** | http://localhost:9091 | Las métricas en crudo, si alguien pregunta de dónde salen |

**Kibana pide un paso la primera vez:** ☰ → *Stack Management* → *Data Views* → *Create*,
patrón `cobre-notifications-*`, campo de tiempo `@timestamp`. Después, *Discover*.

Grafana ya viene con la fuente de datos y el tablero cargados: se abre y funciona.

**Qué muestra el tablero**

| Panel | Para qué sirve |
|---|---|
| Entregadas · Fallidas | El resultado neto |
| **Reintentos exitosos** | Notificaciones que fallaron y se recuperaron con el backoff. Es el número que justifica toda la estrategia: sin reintentos se habrían perdido |
| **Reintentos agotados** | Las que se reintentaron hasta el final y aun así fallaron. Requieren intervención |
| Reenvíos manuales | Si crece, algo estructural está fallando |
| **Errores por código de respuesta** | Con qué rechazan los destinos. Un 5xx es transitorio; un 4xx es contrato roto |
| **Latencia del webhook** | p50, p95 y p99. Es lo primero que se degrada antes de los timeouts |
| A la primera frente a recuperadas | Si la franja de recuperadas crece, los destinos se degradan aunque el resultado final siga siendo bueno |

**Para poblarlo en la demostración**, el receptor decide según el identificador del evento:

| El id contiene | Qué hace el destino |
|---|---|
| `FALLA` | Rechaza los 3 intentos: agota el ciclo |
| `RECUPERA` | Rechaza 1 y luego acepta: entrega recuperada |
| cualquier otra cosa | Acepta a la primera |

> **Sobre Datadog.** Está cableado pero apagado, porque necesita cuenta y API key. Grafana
> muestra exactamente las mismas métricas: el `MetricsPort` publica una sola vez y
> Micrometer alimenta a los registries activos. Cambiar de uno a otro es configuración.

### Logs estructurados en Kibana

**Un flujo completo son ocho líneas**, no ochenta. Cada una identifica qué es y trae lo
que sirve para monitorear:

```
negocio   -                    -          Token emitido para el cliente CLIENT002
api       -                    -          POST /oauth/token          -> 200 (103ms)
api       -                    CLIENT002  GET /notification_events   -> 200 (15ms)
negocio   EVT-LIMPIO-1785980   CLIENT002  Evento aceptado y encolado para entrega
delivery  EVT-LIMPIO-1785980   CLIENT002  Entrega saliente           -> 200 (4ms)
negocio   EVT-LIMPIO-1785980   CLIENT002  Notificación entregada en el intento 1
```

El campo `log_type` separa **`api`** (una petición que entró, con su respuesta),
**`delivery`** (un intento saliente hacia el webhook) y el resto, que es traza de
negocio. En Kibana se filtra con `log_type : "api"` y ya.

**Lo que se quitó a propósito**, y por qué:

| Se eliminó | Razón |
|---|---|
| Los once campos `host.*` | Describían el contenedor de Filebeat, no la máquina del servicio |
| `log.file.inode`, `device_id`, `offset` | Detalles del archivo, no del negocio |
| `agent.*`, `input.*`, `ecs.version` | Metadatos del propio recolector |
| `process.pid` y `process.thread.name` | Irrelevantes para diagnosticar una transacción o un error |
| Logs de Flyway, Spring Data y Netty | Ruido de arranque; `root` está en `WARN` |
| Accesos a `/actuator` | Prometheus consulta cada 5s: serían ~17.000 líneas diarias inútiles |

De **26 campos por documento a 14**. Menos almacenamiento, consultas más rápidas y,
sobre todo, algo que un humano puede leer durante un incidente.


```bash
docker compose --profile observability up -d
```

Kibana en http://localhost:5601 (índice `cobre-notifications-*`).

La aplicación escribe **JSON en formato ECS** y Filebeat lo envía. La app no conoce
Elasticsearch: si el cluster se cae, ni se entera.

Cada línea lleva `request_id`, `client_id` y `event_id` como campos indexados, así que
una consulta reconstruye el ciclo completo de una notificación:

```
negocio   EVT-KIBANA-1   CLIENT002   Evento aceptado y encolado para entrega
delivery  EVT-KIBANA-1   CLIENT002   Entrega saliente  -> 503 (5001ms)
negocio   EVT-KIBANA-1   CLIENT002   Entrega falló (intento 1): reintento en PT3.38S
negocio   EVT-KIBANA-1   CLIENT002   Notificación entregada en el intento 2
```

Los campos de correlación viajan por el contexto reactivo hasta el MDC. Es lo que
permite que una entrega que empieza en el hilo del broker y termina en el de Netty
—segundos después, tras un reintento— conserve el mismo `event_id`. Sin
`context-propagation`, ese campo saldría vacío y los logs no se podrían correlacionar.

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
