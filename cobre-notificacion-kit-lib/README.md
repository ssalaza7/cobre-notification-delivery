# cobre-notificacion-kit-lib

Librería compartida por los tres servicios. **No arranca y no produce contenedor**: se compila
dentro de cada jar ejecutable.

## Qué contiene

```
domain/                     modelo, reglas y estados. Sin framework.
application/port/           contratos de entrada y salida
infrastructure/             adaptadores que implementan esos contratos
```

**`domain`** — qué es una notificación, en qué estados puede estar y qué transiciones son
válidas: `NotificationEvent`, `DeliveryStatus`, `RetryPolicy`, `Subscription`.

Se comparte porque los tres servicios operan sobre las mismas tablas y la misma máquina de
estados.

**`application/port`** — las interfaces que el dominio necesita del exterior:
`NotificationEventRepositoryPort`, `DeliveryQueuePort`, `MetricsPort`. Son contratos, no
lógica. Los casos de uso viven en cada servicio, no aquí.

**`infrastructure`** — persistencia DynamoDB, adaptador de la cola SQS, métricas de Micrometer y
las utilidades de log. Es lo que implementa los puertos.

## La regla que la sostiene

El paquete `domain` no puede importar ningún framework. Es la afirmación central de la
arquitectura hexagonal, y aquí la verifica una prueba:

```
DominioSinFrameworkTest
```

Lee los fuentes de `domain` y de `application/port` y **falla el build** si encuentra un import
de Spring, Kafka, Micrometer, el SDK de AWS o Jackson.

Reactor queda fuera de la lista: es una librería de composición asíncrona, no un framework.

## Qué entra aquí

Lo que necesitan **al menos dos** servicios.

| En el kit | Por qué |
|---|---|
| DynamoDB | Los tres leen y escriben notificaciones, bitácora y suscripciones |
| Adaptador SQS | El consumer encola, el worker reencola, la api encola reenvíos |
| Micrometer, MDC, enmascarado | Los tres publican métricas y escriben logs |

Fuera quedan Kafka (solo el consumer), el cliente HTTP y la firma HMAC (solo el worker) y
Spring Security (solo la api).

## Migraciones

No hay migraciones: las tablas de DynamoDB las declara la infraestructura y en local las crea
`DynamoDbTableInitializer` al arrancar. Las credenciales de
demostración están en `db/demo`, que solo cargan los perfiles `local` y `demo`.

---

Las decisiones de diseño y sus alternativas están en el [documento de diseño](../docs/01-diseno-del-sistema.md).
