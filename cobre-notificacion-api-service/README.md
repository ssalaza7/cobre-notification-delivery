# cobre-notificacion-api-service

API self-service: el cliente consulta sus notificaciones y reenvía las que fallaron.

Es el único módulo con superficie HTTP entrante y, por tanto, el único que necesita cadena de
seguridad, emisión de tokens y límite de peticiones.

```
cliente  ──►  api  ──►  DynamoDB     (notificaciones, intentos y suscripciones)
                  └──►  Proveedor OIDC (reenvia el token; no guarda credenciales)
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

El endpoint sigue aquí, pero la emisión la hace un proveedor OIDC: la API reenvía lo
presentado y devuelve lo que conteste. No guarda credenciales ni firma tokens. Así el cliente
integra contra una sola URL y cambiar de proveedor no le rompe nada.

Defensas propias: un único mensaje de error para todos los fallos, límite por dirección de
origen, y enmascarado de los parámetros sensibles en el log de acceso.

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
    oidc:
      issuer-uri: ${OIDC_ISSUER_URI}   # Keycloak en local, Cognito en AWS
      jwk-set-uri: ${OIDC_JWK_SET_URI}
      token-uri: ${OIDC_TOKEN_URI}
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
