# Guía de uso

Documentación operativa del servicio. El [README](../README.md) describe la arquitectura y
los escenarios de entrega; este documento cubre su manejo.

- [Registro de webhooks](#registro-de-webhooks)
- [Tokens de acceso](#tokens-de-acceso)
- [Receptor de pruebas](#receptor-de-pruebas)
- [Colección de Postman](#colección-de-postman)
- [Consolas de observabilidad](#consolas-de-observabilidad)
- [Aislamiento entre clientes](#aislamiento-entre-clientes)

---

## Registro de webhooks

El cliente administra los suyos con su propio token, igual que consulta sus notificaciones.

```bash
curl -X POST http://localhost:8080/subscriptions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"webhook_url":"https://mi-sistema.com/hooks","event_type":"credit_transfer"}'
```

```json
{
  "event_type": "credit_transfer",
  "webhook_url": "https://mi-sistema.com/hooks",
  "active": true,
  "signing_secret": "whsec_6FWOoTBmTn2yKCoRCR1eaOOtEPI9hrWQqFeYTwe4SP4"
}
```

El `signing_secret` viaja **solo en esta respuesta**. Con él el cliente verifica la cabecera
`X-Cobre-Signature` de cada notificación. En el listado no aparece: devolverlo en cada consulta
lo expondría en cada log, caché y captura de pantalla.

El `client_id` sale del token y nunca del cuerpo. Si viniera en el cuerpo, cualquiera podría
registrar un webhook a nombre de otro y desviarse sus notificaciones.

### Consulta

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/subscriptions
```

### Actualizar el destino

El mismo `POST` con otra URL. **El secreto de firma no cambia**: rotarlo en cada cambio de URL
invalidaría la verificación en el cliente sin previo aviso.

### Un destino por tipo de evento

Omitir `event_type` registra el comodín `*`, que cubre todo lo que no tenga suscripción
específica. Al resolver el destino, el tipo concreto tiene prioridad sobre el comodín.

```bash
# transferencias a un sistema, alertas de saldo a otro
-d '{"webhook_url":"https://pagos.mi-sistema.com/hooks","event_type":"credit_transfer"}'
-d '{"webhook_url":"https://alertas.mi-sistema.com/hooks","event_type":"balance_updated"}'
```

### Permiso

Administrar suscripciones exige el scope `subscriptions:manage`, distinto del de lectura: quien
solo consulta no debe poder redirigir a dónde se entregan las notificaciones. `CLIENT003` no lo
tiene, y recibe 403.

### Redirección global, solo si hace falta

```bash
WEBHOOK_OVERRIDE_URL=https://el-destino/webhook \
java -jar cobre-notificacion-worker-service/build/libs/cobre-notificacion-worker-service-0.0.1-SNAPSHOT.jar
```

Tiene precedencia sobre las suscripciones almacenadas. Está previsto para escenarios en los que
la URL de destino se conoce en el momento de la ejecución.

### Validación del destino

Fuera del perfil `local`, la validación opera en modo estricto: exige HTTPS y rechaza los
destinos que resuelvan a direcciones de red interna (`169.254.169.254`, rangos privados,
loopback). Se aplica dos veces —al registrar y al entregar— porque entre una y otra el DNS
puede cambiar.

Sin esta validación el servicio actuaría como proxy: un cliente podría registrar una URL interna
y obtener que el servicio realizara esa petición desde dentro de la red. Es la vulnerabilidad
SSRF, especialmente relevante aquí porque la URL de destino la define el cliente.

---

## Tokens de acceso

El endpoint admite dos formatos de cuerpo. El primero corresponde al estándar:

```bash
curl -X POST http://localhost:8080/oauth/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&client_id=CLIENT002&client_secret=demo-secret-client002'
```

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/oauth/token \
  -H 'Content-Type: application/json' \
  -d '{"grant_type":"client_credentials","client_id":"CLIENT002","client_secret":"demo-secret-client002"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
```

Credenciales sembradas para demostración:

| client_id | client_secret | scopes |
|---|---|---|
| `CLIENT001` | `demo-secret-client001` | read · replay · monitor |
| `CLIENT002` | `demo-secret-client002` | read · replay · monitor |
| `CLIENT003` | `demo-secret-client003` | solo read |

`CLIENT003` carece de permiso de reenvío de forma deliberada: la consulta y el reenvío son
autorizaciones independientes.

### Método POST

Un `GET` transportaría el `client_secret` en la URL. Las URLs quedan registradas en el log de
acceso del balanceador, en el historial del navegador, en la cabecera `Referer` y en los
proxies intermedios, todos ellos fuera del control del servicio. El RFC 6749 exige POST para
el flujo `client_credentials`, y es la forma que implementan las pasarelas de pago del
mercado.

### Enmascaramiento en los logs

El servicio no transmite secretos por la URL, pero una integración incorrecta del cliente sí
puede hacerlo. El log de acceso sustituye el valor de los parámetros sensibles antes de
escribir la línea:

```
GET /notification_events?size=1&client_secret=***&access_token=***
```

El nombre del parámetro se conserva de forma intencionada: permite detectar la integración
defectuosa y notificarla. Un secreto que llega al índice queda replicado en cada copia de
seguridad y obliga a rotarlo.

---

## Destino de las notificaciones

Para una demostración con aspecto real conviene un destino HTTPS público. **webhook.site** da
uno gratis: entrar, copiar la URL y registrarla.

```bash
curl -X POST http://localhost:8080/subscriptions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"webhook_url":"https://webhook.site/<tu-uuid>"}'
```

Con eso basta: el destino sale de la suscripción y no hace falta reiniciar nada.

Cada notificación aparece en el navegador al instante, con sus cabeceras:

```
X-Cobre-Timestamp        1786067224
X-Cobre-Signature        fb23ed5fb5c0b8f91cf9a05b50d5ad546e4a9a2a0aaf894da568f2d6a1c570a9
X-Cobre-Event-Id         EVT-REAL-1786067223
X-Cobre-Delivery-Attempt 1
```

Es HTTPS real, de modo que sirve también con el perfil `demo`, que exige HTTPS y bloquea
destinos internos.

**Lo que no hace:** verificar la firma. Comprobar la cabecera es responsabilidad del receptor,
y webhook.site solo la muestra. Cobre le pide lo mismo a sus integradores: firmar es del emisor,
validar es del cliente. Para ver la verificación en pantalla está el receptor de pruebas.

---

## Receptor de pruebas

`scripts/webhook-receiver.py` cumple el papel del cliente: recibe la notificación, verifica la
firma y responde. No forma parte del sistema; simula el sistema receptor, que es quien
determina si acepta o rechaza la entrega.

Para demostrar los distintos comportamientos —entrega correcta, reintentos, fallo definitivo,
timeout— sin reiniciar el receptor entre uno y otro, el receptor determina su respuesta a
partir del identificador del evento. Con una única instancia en ejecución, el escenario se
selecciona en el momento de publicar:

| Identificador publicado | Respuesta del receptor | Comportamiento demostrado |
|---|---|---|
| `EVT-DEMO-1` | 200 | Entrega correcta en el primer intento |
| `EVT-RECUPERA-1` | 503 y después 200 | Entrega recuperada por el backoff |
| `EVT-FALLA-1` | 503 siempre | Reintentos agotados: estado `failed` y derivación a la DLQ |
| `EVT-RECHAZA-1` | 400 | Fallo permanente: no se reintenta |
| `EVT-LENTO-1` | sin respuesta a tiempo | Timeout del destino |

Es una convención del receptor de pruebas, no del servicio. El servicio procesa todos los
eventos de forma idéntica.

---

## Colección de Postman

En [postman/](../postman/). Diez peticiones en cuatro carpetas, en orden de ejecución: las
variables se encadenan solas, así que basta recorrerla de arriba abajo.

| Carpeta | Peticiones | Contenido |
|---|---|---|
| 1 · Preparación | 3 | Token, registro del webhook y consulta de las suscripciones |
| 2 · Flujo exitoso | 1 | Publicar un evento. **Es lo único que se hace**: el resto es autónomo |
| 3 · Flujo con reintento | 1 | Publicar un evento cuyo destino rechaza |
| 4 · Consulta y reenvío | 5 | Listado, filtro, detalle, reenvío y comprobación de la bitácora |

Entre la carpeta 3 y la 4 conviene esperar unos quince segundos, el tiempo que tarda el ciclo
de reintentos en agotarse. La petición 4.2 toma la notificación fallida sobre la que trabajan
las siguientes, de modo que el reenvío actúe siempre sobre una que lo esté de verdad.

Cada petición lleva comprobaciones. Se puede ejecutar entera desde el *Collection Runner* o
desde la terminal:

```bash
npx newman run postman/cobre-notification-delivery.postman_collection.json
```

Los identificadores de evento se generan con la marca de tiempo, así que la colección se puede
ejecutar tantas veces como haga falta sin chocar con lo anterior.

---

## Consolas de observabilidad

```bash
docker compose --profile observability up -d
```

| Proceso | Puerto |
|---|---|
| `api` | 8080 |
| `worker` | 8081 |
| `consumer` | 8083 |

Los tres exponen `/actuator/health` y `/actuator/prometheus`. El consumidor y el worker no
sirven tráfico de negocio por HTTP: el servidor existe para las sondas del orquestador y para
que Prometheus pueda raspar sus métricas.

| Consola | URL |
|---|---|
| Grafana — el tablero se aprovisiona automáticamente | http://localhost:3000 |
| Kibana — las vistas se cargan con `./scripts/kibana-import.sh` | http://localhost:5601 |
| SQS — cola de entrega y DLQ | http://localhost:9324 |
| Prometheus — métricas en crudo | http://localhost:9091 |

Las vistas de Kibana están versionadas en
[`observability/kibana/vistas.ndjson`](../observability/kibana/vistas.ndjson), de modo que no
se pierden al recrear el contenedor. Son cinco, disponibles en *Discover → Open*: todo el
tráfico, entregas, llamadas a la API, solo errores y traza de un evento.

El renderizador de imágenes de Grafana está en un perfil aparte (`--profile evidencia`).
Solo se necesita para regenerar las capturas de la documentación.

### Flujo de los datos

La aplicación no envía métricas a ningún destino: las publica en `/actuator/prometheus`, que
refleja el estado en el instante de la consulta. Prometheus consulta ese endpoint cada cinco
segundos y almacena cada lectura, construyendo el histórico. Grafana consulta a Prometheus, no
a la aplicación.

Con los logs el último tramo se invierte: la aplicación escribe JSON en formato ECS, Filebeat
lo lee y lo envía a Elasticsearch, y Kibana consulta el índice.

En ambos casos la aplicación desconoce el destino final. Por eso sustituir Prometheus por
Datadog no requiere modificar el código: el agente de Datadog consulta el mismo endpoint. La
integración con Datadog está implementada y desactivada por defecto, ya que requiere cuenta y
clave de API.

### Paneles del tablero

| Panel | Función |
|---|---|
| Entregadas · Fallidas | Resultado neto |
| Reintentos exitosos | Entregas recuperadas por el backoff. Cuantifican el valor de la estrategia de reintentos |
| Reintentos agotados | Entregas fallidas de forma definitiva. Requieren intervención |
| Reenvíos manuales | Un crecimiento sostenido indica un problema estructural |
| Errores por código | Un 5xx es transitorio; un 4xx indica contrato incumplido |
| Latencia del webhook | p50, p95 y p99. Es el primer indicador que se degrada, antes de los timeouts |
| A la primera frente a recuperadas | El crecimiento de las recuperadas indica degradación de los destinos aunque el resultado final se mantenga |
| Clientes con entregas fallando | Identifica el cliente afectado, no solo el volumen de fallos |
| Sin respuesta del cliente | Timeouts y conexiones rechazadas: el destino no respondió |
| Tiempo de respuesta promedio | Tendencia general, complementaria a los percentiles |

### Uso de `client_id` como etiqueta

Una sola métrica lo incluye: `cobre_notification_client_failures_total`. La decisión responde
a dos restricciones opuestas.

Etiquetar todas las métricas por cliente multiplica el número de series temporales por el
número de clientes. A escala de miles compromete a Prometheus, y en Datadog cada combinación
de etiquetas se factura.

No incluirlo en ninguna métrica impide responder de forma automática la primera pregunta de un
incidente: qué cliente está afectado. Obligaría a consultar los logs y no permitiría definir
alertas.

La solución adoptada acota la etiqueta a la métrica de fallo. La serie se crea únicamente
cuando un cliente registra un fallo, de modo que la cardinalidad no la determina el total de
clientes sino los que presentan fallos en ese momento. Con esa métrica es posible definir una
alerta del tipo «CLIENT002 acumula cinco minutos de fallos» y escalarla al equipo
correspondiente.

---

## Aislamiento entre clientes

`EVT005` pertenece a `CLIENT003`. Consultado con un token de `CLIENT002`:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/notification_events/EVT005
# 404
```

La respuesta es 404 y no 403. Un 403 confirmaría la existencia del recurso y permitiría
enumerar identificadores ajenos. Hacia el exterior, «no existe» y «no pertenece al
solicitante» son indistinguibles.

El `client_id` se obtiene siempre del token, nunca de la ruta ni de la query. No existe ningún
parámetro que permita expresar una consulta sobre los datos de otro cliente.
