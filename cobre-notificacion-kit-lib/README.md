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

Está aquí porque los tres servicios operan sobre las mismas tablas y la misma máquina de
estados. La api consulta `isReplayable()` para responder 409, y el worker respeta esa misma
regla al cerrar el estado. Duplicarlo en cada servicio no daría independencia: daría dos
definiciones de la misma regla, listas para divergir.

**`application/port`** — las interfaces que el dominio necesita del exterior:
`NotificationEventRepositoryPort`, `DeliveryQueuePort`, `MetricsPort`. Son contratos, no
lógica. Los casos de uso viven en cada servicio, no aquí.

**`infrastructure`** — persistencia R2DBC, adaptador de la cola SQS, métricas de Micrometer y
las utilidades de log. Es lo que implementa los puertos.

## La regla que la sostiene

El paquete `domain` no puede importar ningún framework. Es la afirmación central de la
arquitectura hexagonal, y aquí la verifica una prueba:

```
DominioSinFrameworkTest
```

Lee los fuentes de `domain` y de `application/port` y **falla el build** si encuentra un import
de Spring, R2DBC, Kafka, Micrometer, el SDK de AWS o Jackson.

Existe porque el dominio comparte módulo con los adaptadores, así que esas librerías están en
su classpath y el compilador ya no lo impide por sí solo. Reactor queda fuera de la lista a
propósito: es una librería de composición asíncrona, no un framework — no impone contenedor,
ciclo de vida ni configuración.

## Qué entra aquí y qué no

Entra lo que necesitan **al menos dos** servicios. Cada dependencia añadida la cargan los tres
jars aunque dos no la usen.

| En el kit | Por qué |
|---|---|
| R2DBC | Los tres leen y escriben las mismas tablas |
| Adaptador SQS | El consumer encola, el worker reencola, la api encola reenvíos |
| Micrometer, MDC, enmascarado | Los tres publican métricas y escriben logs |

Fuera quedaron, por la misma regla: Kafka (solo el consumer), el cliente HTTP y la firma HMAC
(solo el worker), Spring Security (solo la api).

Si mañana un servicio necesita algo que el kit no soporta —otra implementación de
persistencia, por ejemplo— la trae él, sin cambiar el kit.

## Migraciones

Las migraciones de Flyway viven en `src/main/resources/db/migration` y viajan con la librería,
de modo que cualquiera de los tres servicios puede aplicarlas al arrancar. Las credenciales de
demostración están aparte, en `db/demo`, y solo las cargan los perfiles `local` y `demo`.
