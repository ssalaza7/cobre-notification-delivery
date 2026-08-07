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

El `client_id` sale siempre del token. Un recurso ajeno responde **404**. Consultar, reenviar y
administrar suscripciones son scopes distintos.

## Tokens

Flujo `client_credentials` de OAuth2. Acepta el cuerpo como formulario —la forma del RFC 6749—
y también como JSON.

Defensas: secreto en hash bcrypt, un único mensaje de error para todos los fallos, tiempo de
respuesta constante, límite por dirección de origen y vigencia de una hora. El log de acceso
enmascara los parámetros sensibles.

## Reenvío

Responde **202**: la solicitud queda encolada. Solo se reenvía lo que está en `failed`;
cualquier otro estado devuelve 409. Con bloqueo optimista, dos peticiones simultáneas encolan
una sola vez. La bitácora es de solo adición.

## Métricas de backlog

Publica el conteo de eventos por estado como gauge de Micrometer.

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

---

Las decisiones de diseño y sus alternativas están en el [documento de diseño](../docs/01-diseno-del-sistema.md).
