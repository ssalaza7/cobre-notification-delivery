# Task 3 — Seguridad: OWASP Top 10

La API se consume públicamente desde internet. Abajo están las vulnerabilidades del
OWASP Top 10 que considero de mayor impacto real para **esta** API en concreto, con la
mitigación implementada y dónde verla en el código.

El enunciado pide tres; van cinco, porque las dos últimas son específicas de un
servicio de webhooks y dejarlas fuera sería omitir lo más interesante del caso.

---

## Resumen

| # | Vulnerabilidad | Por qué aplica aquí | Estado |
|---|---|---|---|
| A01 | Broken Access Control (BOLA) | Los `event_id` son secuenciales y adivinables | Implementado |
| A07 | Identification & Authentication Failures | API pública sin sesión | Implementado |
| A03 | Injection | Filtros dinámicos construyen SQL | Implementado |
| A10 | SSRF | **El cliente elige la URL que llamamos** | Implementado |
| A04 | Insecure Design (consumo sin límite) | Un cliente puede degradar a todos | Parcial |

---

## A01 · Broken Access Control — el riesgo número uno de esta API

### El problema

Los identificadores del archivo de la prueba son `EVT001`, `EVT002`, `EVT003`. Son
secuenciales y adivinables. Si el endpoint de detalle resolviera el recurso solo por
su id, cualquier cliente autenticado podría recorrer `EVT001..EVT999` y leer las
notificaciones de todos los demás: montos, contrapartes y patrones de pago de empresas
competidoras.

Esto es **BOLA** (Broken Object Level Authorization), la primera de la lista del OWASP
API Top 10 y la más común en APIs reales, porque la autenticación funciona
perfectamente — el atacante *es* un cliente legítimo — y solo falla la autorización
sobre el objeto.

### Mitigación

**1. El tenant sale del token, nunca de la petición.**

```java
// NotificationEventController
return getUseCase.get(notificationEventId, AuthenticatedClient.clientIdOf(jwt));
```

No existe ningún parámetro `client_id` en ninguna ruta ni query string. No hay forma
de *expresar* la petición insegura.

**2. El modelo hace imposible la consulta sin acotar.**

`EventQuery` exige `clientId` en su constructor y falla si viene vacío. No es
validación defensiva: es que el objeto no se puede construir mal. La autorización deja
de depender de que alguien recuerde poner un `if`.

**3. El filtro va en la consulta, no después.**

```sql
SELECT ... FROM notification_event WHERE event_id = :eventId AND client_id = :clientId
```

Filtrar en SQL y no en memoria evita la variante clásica del error: traer la fila y
comparar después, que falla en cuanto alguien agrega un camino que olvida comparar.

**4. Un recurso ajeno responde 404, no 403.**

Un 403 confirma que el recurso existe y convierte la API en un oráculo para enumerar
identificadores ajenos. Hacia afuera, "no existe" y "no es tuyo" son indistinguibles.

**5. Permisos separados por operación.** Consultar y reenviar son scopes distintos: un
token de un panel de consulta no puede disparar reenvíos.

**Verificado en ejecución** — `EVT005` pertenece a `CLIENT003`:

```
EVT005 con token de CLIENT002 -> 404
EVT003 con token de CLIENT002 -> 200
```

📁 `NotificationEventController`, `AuthenticatedClient`, `EventQuery`,
`NotificationEventNotFoundException`, `SecurityConfig`

---

## A07 · Identification and Authentication Failures

### El problema

Una API pública sin sesión: si la autenticación es débil o hay un endpoint que se
quedó abierto por descuido, todo lo demás sobra.

### Mitigación

**Todo cerrado por defecto.** La última regla de la cadena es `denyAll()`:

```java
.pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
.pathMatchers("/actuator/**").hasAuthority(SCOPE_MONITOR)
.pathMatchers(POST, "/notification_events/*/replay").hasAuthority(SCOPE_REPLAY)
.pathMatchers(GET,  "/notification_events/**").hasAuthority(SCOPE_READ)
.anyExchange().denyAll()
```

Un endpoint nuevo **nace protegido**. Si la última regla fuera `permitAll()`, nacer sin
protección sería el comportamiento por omisión — y ese es exactamente el descuido que
produce las brechas.

**Validación estricta del token.** Firma HS256 verificada, algoritmo fijado
explícitamente (evita el ataque de confusión de algoritmo, incluido `alg: none`),
expiración validada por Nimbus.

**Sin claim, sin identidad.** Un token válido pero sin `client_id` se rechaza en vez de
asumir un valor por defecto; cualquier valor asumido sería una vía para leer datos
ajenos.

**Longitud mínima de clave.** El arranque falla si el secreto tiene menos de 32 bytes:
HS256 con clave más corta que el hash es fuerza bruta viable.

**Solo `health` e `info` son públicos**, porque los necesitan las sondas de Kubernetes.
Métricas y demás exigen scope propio — un `/actuator/prometheus` abierto entrega el
mapa operativo del sistema. Verificado: devuelve 401 sin token.

### Lo que falta para producción

HS256 con secreto compartido es adecuado para esta prueba, no para producción. El paso
natural es **validación por JWKS contra el IdP de Cobre**: rota claves sin redesplegar
y el servicio deja de conocer ningún secreto de firma.

📁 `SecurityConfig`, `AuthenticatedClient`

---

## A03 · Injection

### El problema

El listado arma su `WHERE` dinámicamente según qué filtros lleguen. Es justo el patrón
donde suele aparecer la concatenación de strings y, con ella, la inyección SQL.

### Mitigación

**Todo valor variable viaja como parámetro enlazado.** Lo único que se concatena son
fragmentos literales escritos en el código:

```java
if (query.deliveryStatus() != null) {
    where.append(" AND delivery_status = :deliveryStatus");   // literal del código
    params.put("deliveryStatus", TypedValue.of(...));          // valor enlazado
}
```

La entrada del usuario nunca toca la cadena SQL.

**El enum se valida antes de tocar la base.** Un `delivery_status` desconocido se
rechaza con 400 listando los valores soportados; nunca llega a la consulta.

**Paginación acotada.** `size` entre 1 y 100 — evita el `LIMIT` gigante que convierte
un filtro en una descarga completa de la tabla.

📁 `R2dbcNotificationEventRepositoryAdapter.buildWhere`, `SqlBindings`, `EventQuery`

---

## A10 · SSRF — la más peligrosa en un servicio de webhooks

### El problema

Este servicio hace peticiones HTTP salientes **a una URL que elige el cliente**. Esa es
la definición literal de SSRF. Sin control, un cliente registra su webhook apuntando a:

- `http://169.254.169.254/latest/meta-data/iam/security-credentials/` — el endpoint de
  metadatos de AWS, es decir, **credenciales IAM del rol de la tarea**
- `http://10.0.x.x/` — cualquier servicio interno de la VPC
- `http://localhost:5432/` — la propia base de datos

Y el servicio haría esas peticiones **desde dentro de la red**, con la identidad de la
aplicación. En una plataforma de pagos regulada, esto es de los peores escenarios
posibles.

### Mitigación

**1. Solo HTTPS.** Se rechaza cualquier otro esquema — incluidos `file://` y `gopher://`,
clásicos para leer archivos locales o hablar con protocolos internos.

**2. Se resuelve el host y se rechaza toda dirección no pública:**

```java
address.isLoopbackAddress()   // 127.0.0.0/8
  || address.isLinkLocalAddress()   // 169.254.0.0/16 ← metadatos de la nube
  || address.isSiteLocalAddress()   // 10/8, 172.16/12, 192.168/16
  || address.isAnyLocalAddress()
  || address.isMulticastAddress()
```

Se comprueban **todas** las direcciones que devuelve el DNS, no solo la primera: un
atacante puede publicar un registro con una IP pública y otra privada.

**3. No se siguen redirecciones.** Detalle crítico y fácil de pasar por alto: sin esto,
un atacante registra un destino público perfectamente válido que responde
`302 → http://169.254.169.254/...`, y el cliente HTTP evade toda la validación
anterior. Aquí un 3xx se trata como fallo permanente.

```java
.followRedirect(false)
```

**4. La resolución DNS corre fuera del event loop.** Es bloqueante; en `boundedElastic`
para no congelar los hilos de Netty.

**5. Defensa en profundidad en AWS.** Los controles de aplicación no bastan: en la nube
se exige **IMDSv2** (que rompe el ataque de metadatos), el egreso sale solo por NAT
Gateway y los grupos de seguridad restringen a dónde puede hablar la tarea.

**6. Se acota el cuerpo de respuesta** que se lee de un tercero, para que un destino
malicioso no agote la memoria del worker.

📁 `WebhookUrlValidator`, `WebClientWebhookAdapter`, `AppConfig.webhookWebClient`

### Y el reverso: autenticar lo que enviamos

Sin firma, el receptor no puede distinguir una notificación de Cobre de una fabricada
por cualquiera que conozca su URL — que en un servicio de pagos significa aceptar
"recibiste $10.000.000" de un desconocido.

Cada entrega va firmada con **HMAC-SHA256**: `X-Cobre-Signature: t=<epoch>,v1=<hex>`.

El instante va **dentro del contenido firmado**, no solo en una cabecera aparte: así el
receptor puede rechazar la reproducción de una captura antigua sin que un atacante
pueda alterar la marca de tiempo. La comparación de firmas es en tiempo constante
(`MessageDigest.isEqual`) para no filtrar por temporización cuántos bytes coincidían.

📁 `WebhookSigner`

---

## A04 · Insecure Design — consumo de recursos sin límite

### El problema

Sin límite, un solo cliente con un bucle mal escrito degrada la API para todos. Es
denegación de servicio sin necesidad de mala intención.

### Mitigación

Ventana fija por cliente autenticado, 120 peticiones por minuto, respondiendo 429 con
`Retry-After`. La cuota se lleva por `client_id` del token, así que un cliente abusivo
no consume la de los demás.

### Limitación, dicha explícitamente

**El contador vive en memoria: el límite real es por instancia.** Con tres réplicas, el
límite efectivo es el triple. Contiene el abuso accidental, no un ataque deliberado.

La solución correcta es que el límite viva en el borde. En AWS, **AWS WAF con una regla
basada en tasa** corta el tráfico *antes* de que llegue al servicio, que es donde debe
cortarse: un límite dentro de la aplicación ya pagó el costo de aceptar la conexión.

📁 `RateLimitWebFilter`

---

## Transversales

**Los errores no filtran nada.** El manejador genérico devuelve un identificador de
correlación y deja el detalle en los logs. Una traza o un mensaje de driver en la
respuesta le regala al atacante el mapa del sistema (A05).

Verificado — ante `IllegalStateException("connection to postgres://cobre:cobre@db:5432 failed")`:

```json
{"status":500,"title":"Error interno",
 "detail":"Ocurrio un error inesperado. Reporte el identificador d75bd035-..."}
```

**Datos sensibles fuera de los logs.** El `content` de la notificación —
`"Credit card payment received for $150.00"` — **nunca** se registra. Es dato
financiero del cliente; mandarlo a un índice de logs lo replica en un sistema con otra
retención, otro control de acceso y otro respaldo. En una compañía con PCI DSS e
ISO 27001, eso amplía el alcance de auditoría sin ganar nada.

**CSRF desactivado, y es correcto.** La API es sin estado y se autentica con Bearer, no
con cookies. Sin cookies de sesión no hay vector CSRF que proteger; dejarlo activo solo
añadiría ruido.

**Cabeceras de seguridad**: HSTS a un año con subdominios, `X-Content-Type-Options`,
`X-Frame-Options: DENY`.

**Sin CORS.** Es una API servidor a servidor. No habilitar CORS es una decisión, no un
olvido: habilitarlo invitaría a que alguien ponga el token en un navegador.

---

## Qué haría a continuación

| Prioridad | Acción |
|---|---|
| Alta | JWKS contra el IdP en lugar de HS256 con secreto compartido |
| Alta | Cifrar los `signing_secret` con KMS o moverlos a Secrets Manager |
| Alta | Rate limiting en WAF, no en la aplicación |
| Media | Rotación de secretos de firma con periodo de gracia de dos claves |
| Media | Bitácora de auditoría de reenvíos: quién reenvió qué y cuándo |
| Media | mTLS opcional para clientes que lo exijan |
| Baja | Lista blanca de dominios de webhook por cliente |
