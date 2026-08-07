# cobre-notificacion-api-service

API self-service: el cliente consulta sus notificaciones y reenvía las que fallaron.

Es el único módulo con superficie HTTP entrante y, por tanto, el único que necesita cadena de
seguridad, emisión de tokens y límite de peticiones.

```
cliente  ──►  api  ──►  PostgreSQL   (consulta y actualiza)
                   ──►  SQS          (encola el reenvío)
```

## Endpoints

```
POST /oauth/token                      emisión de token
GET  /notification_events              listado con filtros y paginación
GET  /notification_events/{id}         detalle con la bitácora de cada intento
POST /notification_events/{id}/replay  reenvío de una entrega fallida
```

Puerto 8080.

## Autorización

El `client_id` sale siempre del token, nunca de la ruta ni de la query. No existe ningún
parámetro que permita expresar una consulta sobre los datos de otro cliente, y `EventQuery`
exige el tenant en su constructor: la petición insegura no se puede construir.

Un recurso ajeno responde **404, no 403**. Un 403 confirmaría que el recurso existe y
convertiría la API en un oráculo para enumerar identificadores.

Consultar y reenviar son scopes distintos: un panel de solo lectura no puede disparar reenvíos.

## Tokens

Flujo `client_credentials` de OAuth2. Acepta el cuerpo como formulario —la forma del RFC 6749—
y también como JSON.

Es el único endpoint público y el más expuesto a fuerza bruta. Sus defensas: secreto guardado
solo como hash bcrypt, un único mensaje de error para todos los modos de fallo, verificación en
vacío cuando el cliente no existe para igualar tiempos, límite por dirección de origen y
vigencia de una hora.

El log de acceso enmascara los parámetros sensibles antes de escribir la línea, por si una
integración manda un secreto por la URL.

## Reenvío

Responde **202 y no 200**: la solicitud queda encolada, no entregada. Si la API entregara en
línea, la petición quedaría atada al tiempo de respuesta del webhook del cliente y perdería los
reintentos, la DLQ y la bitácora.

Solo se reenvía lo que está en `failed`; cualquier otro estado devuelve 409. El reenvío usa
bloqueo optimista, de modo que dos peticiones simultáneas encolan una sola vez.

La bitácora es de solo adición: el reenvío conserva los intentos del ciclo anterior.

## Métricas de backlog

Este módulo hospeda el refrescador que publica el conteo de eventos por estado. Es observación
pura y vive aquí, no en el worker, para que una consulta periódica de conteo no compita por
capacidad con la entrega.

## Configuración

```yaml
cobre:
  security:
    jwt:
      secret: ${JWT_SECRET}          # sin valor por defecto: no arranca sin él
      token-ttl: 1h
    rate-limit:
      requests-per-minute: 120
      token-requests-per-minute: 10
```

## Ejecución

```bash
./gradlew :cobre-notificacion-api-service:bootJar

SPRING_PROFILES_ACTIVE=local java -jar \
  cobre-notificacion-api-service/build/libs/cobre-notificacion-api-service-0.0.1-SNAPSHOT.jar
```

## Dependencia propia

Spring Security y el resource server JWT. No incluye Kafka ni el cliente de webhooks.
