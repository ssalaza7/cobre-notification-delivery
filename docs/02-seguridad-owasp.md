# Task 3 — Seguridad: OWASP Top 10

Vulnerabilidades del OWASP Top 10 con mayor impacto real sobre esta API, con la mitigación
implementada y su ubicación en el código. El enunciado pide tres; se documentan cinco porque
las dos últimas son propias de un servicio de webhooks.

| # | Vulnerabilidad | Por qué aplica | Estado |
|---|---|---|---|
| A01 | Broken Access Control (BOLA) | Los `event_id` son secuenciales y adivinables | Implementado |
| A07 | Identification & Authentication Failures | API pública sin sesión | Implementado |
| A03 | Injection | Los filtros y el cursor se arman dinámicamente | Implementado |
| A10 | SSRF | El cliente elige la URL de destino | Implementado |
| A04 | Insecure Design (consumo sin límite) | Un cliente puede degradar el servicio para todos | Parcial |

---

## A01 · Broken Access Control

Los identificadores del archivo de la prueba son `EVT001`, `EVT002`, `EVT003`. Si el endpoint
de detalle resolviera el recurso solo por su id, cualquier cliente autenticado podría recorrer
el rango y leer montos, contrapartes y patrones de pago de otras empresas. Es **BOLA**: la
autenticación funciona —el atacante es un cliente legítimo— y solo falla la autorización sobre
el objeto.

**Mitigación**

1. **El tenant sale del token.** No existe parámetro `client_id` en ninguna ruta ni query. La
   petición insegura no se puede expresar.
   ```java
   return getUseCase.get(notificationEventId, AuthenticatedClient.clientIdOf(jwt));
   ```
2. **El modelo la hace imposible.** `EventQuery` exige `clientId` en su constructor. La
   autorización no depende de recordar una comprobación.
3. **El filtro vive en el puerto**, no en quien lo llama. La lectura por clave de DynamoDB no
   admite condiciones sobre atributos que no son clave, así que la comprobación de propiedad
   la hace el adaptador sobre el agregado ya leído:
   ```java
   return findById(eventId).filter(event -> event.belongsTo(clientId));
   ```
   La garantía no es que la aplique el motor, sino que el caso de uso no tiene forma de
   obtener un evento sin ella: el puerto solo expone `findByIdAndClientId`, y `belongsTo` es
   una regla del dominio con prueba propia.
4. **Un recurso ajeno responde 404, no 403.** Un 403 confirmaría la existencia del recurso y
   convertiría la API en un oráculo de enumeración.
5. **Scopes separados** para consulta, reenvío y administración de suscripciones. Quien solo
   consulta no puede redirigir a dónde se entregan las notificaciones (`subscriptions:manage`).

Verificado: `EVT005` (de `CLIENT003`) con token de `CLIENT002` devuelve 404; `EVT003` devuelve
200.

📁 `api`: `NotificationEventController`, `AuthenticatedClient`, `SecurityConfig` · `kit`: `EventQuery`, `NotificationEvent.belongsTo`

---

## A07 · Identification and Authentication Failures

**Todo cerrado por defecto.** La última regla de la cadena es `denyAll()`, de modo que un
endpoint nuevo nace protegido:

```java
.pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
.pathMatchers("/actuator/**").hasAuthority(SCOPE_MONITOR)
.pathMatchers(POST, "/notification_events/*/replay").hasAuthority(SCOPE_REPLAY)
.pathMatchers(GET,  "/notification_events/**").hasAuthority(SCOPE_READ)
.anyExchange().denyAll()
```

**Validación estricta del token.** Firma HS256 con algoritmo fijado explícitamente —lo que
descarta el ataque de confusión de algoritmo, incluido `alg: none`—, expiración validada, y
rechazo del token sin claim `client_id` en lugar de asumir un valor por defecto. El arranque
falla si el secreto tiene menos de 32 bytes.

Solo `health` e `info` son públicos, por las sondas del orquestador. `/actuator/prometheus`
exige scope: expuesto entregaría el mapa operativo del sistema.

### Emisión de tokens

La API emite sus propios tokens con el flujo `client_credentials` en `POST /oauth/token`. Es
el único endpoint público y, por tanto, el más expuesto:

| Riesgo | Mitigación |
|---|---|
| Volcado de la base | El servicio no guarda credenciales: las custodia el proveedor de identidad |
| Enumeración de clientes | Un único mensaje de error para todos los modos de fallo |
| Enumeración por temporización | Verificación en vacío cuando el cliente no existe, para igualar tiempos |
| Fuerza bruta | Límite por dirección de origen, aparte del límite por cliente |
| Escalada de privilegios | Los scopes salen de la credencial registrada, no de la petición |
| Secreto en logs y proxies | `POST`, no `GET`; respuesta con `Cache-Control: no-store` |
| Token filtrado | Vigencia de 1 hora. Un JWT no se revoca sin lista de revocación |

En una plataforma real la emisión correspondería a un servicio de identidad central —Cognito
en el despliegue propuesto— y la validación pasaría a **JWKS**: el servicio descargaría las
claves públicas del emisor, que podría rotarlas sin redesplegar, y dejaría de conocer ningún
secreto de firma. Es un cambio de configuración, no de código.

📁 `api`: `SecurityConfig`, `AuthenticatedClient` · `kit`: `IssueAccessTokenService`

---

## A03 · Injection

El listado arma su consulta según los filtros recibidos, que es donde suele aparecer la
concatenación de cadenas. DynamoDB no interpreta SQL, pero sus expresiones se construyen igual
y admiten el mismo error.

**Ningún valor entra en la expresión.** Los nombres de atributo y los valores viajan por sus
mapas, y lo único que se concatena son fragmentos literales escritos en el código:

```java
if (query.deliveryStatus() != null) {
    names.put("#status", NotificationTable.DELIVERY_STATUS);          // literal
    values.put(":status", NotificationTable.s(...));                  // valor enlazado
    request.filterExpression("#status = :status");
}
```

El enum se valida antes de llegar a la consulta —un `delivery_status` desconocido se rechaza
con 400— y el tamaño de página está acotado entre 1 y 100.

**El cursor es entrada del usuario.** Viaja opaco en Base64, y al decodificarlo se comprueba
que su partición sea la del cliente autenticado: pasar el cursor de otro tenant se rechaza con
400, igual que uno corrupto. Sin esa comprobación sería una vía para leer notificaciones
ajenas, que es lo que el resto del diseño impide.

📁 `kit`: `DynamoDbNotificationEventRepositoryAdapter.buildQuery`, `EventCursor`, `EventQuery`

---

## A10 · SSRF

El servicio realiza peticiones HTTP salientes a una URL que elige el cliente. Sin control, una
suscripción puede apuntar a `http://169.254.169.254/latest/meta-data/iam/security-credentials/`
—credenciales IAM del rol de la tarea—, a un servicio interno de la VPC o a la propia base de
datos, y el servicio ejecutaría esa petición desde dentro de la red con su propia identidad.

**Mitigación**

1. **Solo HTTPS.** Se rechaza cualquier otro esquema, incluidos `file://` y `gopher://`.
2. **Se resuelven todas las direcciones del host** —no solo la primera, porque un atacante
   puede publicar una IP pública y otra privada— y se rechaza toda dirección no pública:
   ```java
   address.isLoopbackAddress()       // 127.0.0.0/8
     || address.isLinkLocalAddress() // 169.254.0.0/16 ← metadatos de la nube
     || address.isSiteLocalAddress() // 10/8, 172.16/12, 192.168/16
     || address.isAnyLocalAddress()
     || address.isMulticastAddress()
   ```
3. **No se siguen redirecciones** (`followRedirect(false)`). Sin esto, un destino público
   válido puede responder `302 → http://169.254.169.254/...` y evadir toda la validación
   anterior. Un 3xx se trata como fallo permanente.
4. **La resolución DNS corre en `boundedElastic`**, por ser bloqueante.
5. **Se valida en dos momentos**: al registrar la suscripción, para rechazar de entrada un
   destino inaceptable, y al entregar, porque entre uno y otro el DNS puede cambiar.
6. **Defensa en profundidad en AWS**: IMDSv2 obligatorio, egreso solo por NAT Gateway y grupos
   de seguridad restrictivos.
7. **Cuerpo de respuesta acotado**, para que un destino malicioso no agote la memoria.

### Limitación: es una denylist, no una allowlist

El control enumera lo prohibido, y por naturaleza queda incompleto. Huecos identificados:

| Hueco | Detalle |
|---|---|
| `100.64.0.0/10` | Rango CGNAT. `isSiteLocalAddress()` no lo cubre |
| IPv6 mapeado | `::ffff:169.254.169.254` puede eludir la comprobación según la resolución |
| TOCTOU / DNS rebinding | La validación resuelve el DNS y el cliente HTTP lo resuelve otra vez al conectar. Entre ambas resoluciones el registro puede cambiar a una dirección interna |

**La mitigación correcta es una allowlist por cliente.** No puede ser global —el propósito del
servicio es que el cliente elija su URL— pero sí por tenant: el cliente registra sus dominios
una vez, se verifican mediante un registro TXT en DNS o un challenge sobre su endpoint, y a
partir de ahí solo se aceptan URLs bajo esos dominios. Es el modelo de verificación de dominio
que aplican las plataformas de pago del mercado.

La allowlist de dominios no resuelve por sí sola el TOCTOU: para cerrarlo hay que fijar la
dirección IP validada al establecer la conexión, en lugar de permitir una segunda resolución.

📁 `worker`: `WebhookUrlValidator`, `WebClientWebhookAdapter`, `DeliveryWorkerConfig.webhookWebClient`

### Autenticación de las notificaciones salientes

Sin firma, el receptor no puede distinguir una notificación legítima de una fabricada por
quien conozca su URL. Cada entrega se firma con **HMAC-SHA256**:
dos cabeceras:

```
X-Cobre-Timestamp: 1786067224
X-Cobre-Signature: fb23ed5fb5c0b8f91cf9a05b50d5ad546e4a9a2a0aaf894da568f2d6a1c570a9
```

La firma es el HMAC de `timestamp + "." + cuerpo`.

El instante forma parte del contenido firmado y no solo de una cabecera independiente, lo que
permite al receptor rechazar la reproducción de una captura antigua sin que la marca de tiempo
sea manipulable. La comparación de firmas usa `MessageDigest.isEqual`, en tiempo constante.

📁 `worker`: `WebhookSigner`

---

## A04 · Insecure Design — consumo de recursos sin límite

Ventana fija por cliente autenticado, 120 peticiones por minuto, con respuesta 429 y
`Retry-After`. La cuota se contabiliza por `client_id` del token, de modo que un cliente
abusivo no consume la de los demás.

**Limitación:** el contador reside en memoria, por lo que el límite es por instancia. Con tres
réplicas el límite efectivo se triplica. Contiene el abuso accidental, no un ataque deliberado.
El control correcto vive en el borde —AWS WAF con regla basada en tasa— donde el tráfico se
corta antes de que el servicio pague el costo de aceptar la conexión.

📁 `api`: `RateLimitWebFilter`

---

## Gestión de secretos

Ningún secreto está escrito en el código. Un valor incluido en el repositorio permanece en el
historial de git de forma permanente aunque se elimine después, y se propaga a cualquier clon.

| Dato | Tratamiento | Motivo |
|---|---|---|
| Secreto de firma de webhooks | En el ítem de la suscripción; el candidato natural es un almacén de secretos | Permite falsificar notificaciones hacia el cliente |
| `signing_secret` de webhooks | En base de datos; pendiente de cifrar con KMS | Permite falsificar notificaciones hacia el cliente |
| Secretos de clientes de API | No se almacenan: el proveedor de identidad los custodia | El servicio no puede filtrar lo que no tiene |
| Credenciales de los contenedores locales | Valores por defecto en el repositorio | Contenedores desechables sin acceso a nada; en entornos reales provienen del gestor |

**Inyección.** En local, un archivo `.env` no versionado, con `.env.example` como plantilla. En
AWS, **Secrets Manager** montado como variable de entorno en la definición de tarea de ECS:
permite rotación sin redespliegue, registra cada acceso en CloudTrail, cifra en reposo con KMS
y mantiene el secreto fuera del repositorio y del pipeline. Para configuración no sensible
basta Parameter Store.

**Credenciales de demostración.** Los tres clientes de ejemplo viven en el realm de Keycloak que levanta docker compose, con sus alcances. Sus secretos son de un entorno local desechable y no valen fuera de él; en producción los administra el proveedor de identidad.

**Pendiente:** cifrar el `signing_secret` con KMS; rotación de la clave de firma con ventana de
dos claves; escaneo de secretos en CI (`gitleaks`).

---

## Controles transversales

**Los errores no filtran información.** El manejador genérico devuelve un identificador de
correlación y deja el detalle en los logs. Ante
`IllegalStateException("connection to the identity provider failed")`:

```json
{"status":500,"title":"Error interno",
 "detail":"Ocurrio un error inesperado. Reporte el identificador d75bd035-..."}
```

**Datos sensibles fuera de los logs.** El `content` de la notificación no se registra. Es
información financiera del cliente, y enviarla a un índice de logs la replica en un sistema con
distinta retención, control de acceso y respaldo, ampliando el alcance de auditoría de PCI DSS
e ISO 27001.

**Enmascaramiento en el log de acceso.** Los valores de parámetros sensibles se sustituyen
antes de escribir la línea: `GET /notification_events?client_secret=***`. El nombre del
parámetro se conserva para poder detectar y notificar la integración defectuosa.

**CSRF desactivado.** La API es sin estado y se autentica con Bearer, no con cookies; sin
cookies de sesión no existe vector CSRF.

**Cabeceras**: HSTS a un año con subdominios, `X-Content-Type-Options`, `X-Frame-Options: DENY`.

**Sin CORS.** Es una API servidor a servidor. Habilitarlo invitaría a colocar el token en un
navegador.

---

## Siguientes pasos

| Prioridad | Acción |
|---|---|
| Alta | JWKS contra el IdP en lugar de HS256 con secreto compartido |
| Alta | Allowlist de dominios verificados por cliente, con pinning de la IP resuelta |
| Alta | Cifrar los `signing_secret` con KMS o moverlos a Secrets Manager |
| Alta | Rate limiting en WAF, no en la aplicación |
| Media | Rotación de secretos de firma con periodo de gracia de dos claves |
| Media | Bitácora de auditoría de reenvíos |
| Media | mTLS opcional para clientes que lo exijan |
