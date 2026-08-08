# Especificación de reconstrucción

Este documento contiene lo necesario para reconstruir el servicio desde cero con asistencia
de IA. No describe lo que hay: **instruye qué construir y bajo qué restricciones**.

Existe por dos motivos. El primero es práctico: un repositorio se lee, pero las decisiones que
lo explican no están en el código. El segundo tiene que ver con el uso de IA en esta prueba —
lo que una herramienta produce depende por completo de lo que se le pide, y la especificación
es la parte que no la escribe ella. Entregarla es la forma honesta de mostrar dónde está el
criterio propio y dónde la ejecución.

Un modelo al que se le dé este documento debería llegar a un sistema equivalente. No idéntico:
los nombres y el orden de los archivos variarán. Las garantías, no.

---

## 1. Problema

Una plataforma de pagos genera eventos de negocio —transferencias, cobros, movimientos de
saldo— y sus clientes quieren enterarse sin sondear una API. Hay que construir el servicio que
entrega esos eventos a los webhooks de los clientes.

Lo que se exige:

- Cada cliente recibe **solo** los eventos que le pertenecen.
- Ninguna notificación se pierde porque el destino esté caído.
- El cliente puede consultar qué se le entregó y reenviar lo que falló.
- El destino y su verificación de autenticidad los controla el cliente.

Lo que **no** se pide y no debe construirse: interpretar el contenido del evento, validar
reglas de negocio sobre él, ni ordenar las entregas entre sí.

---

## 2. Restricciones de partida

Son decisiones tomadas antes de escribir código. No deben re-litigarse durante la
implementación.

| Restricción | Por qué |
|---|---|
| **Arquitectura hexagonal** | El dominio y los casos de uso no importan Spring, ni el SDK de AWS, ni driver alguno. Es lo que permitió cambiar de PostgreSQL a DynamoDB y de emisión propia a un proveedor externo sin tocar un solo caso de uso |
| **Reactivo de punta a punta** | El worker espera respuestas de terceros que tardan segundos o no llegan. Con un hilo por entrega, mil destinos lentos son mil hilos bloqueados |
| **Java 21 · Spring Boot 4 · Gradle** | — |
| **Bus de entrada distinto de la cola de trabajo** | Son dos problemas distintos; ver §4 |
| **Entrega al menos una vez** | Un duplicado que el cliente descarta cuesta menos que un pago no notificado |
| **Cobertura mínima del 90 %** | Verificada por el build, no por convención |

---

## 3. Estructura

Cuatro módulos Gradle:

```
kit          dominio, puertos y adaptadores de salida. Lo comparten los tres ejecutables
consumer     consume el bus y encola
worker       entrega, reintenta y registra
api          consulta, reenvío y administración de suscripciones
```

Tres ejecutables y no uno porque escalan por señales distintas: el consumer por retraso del
grupo de consumo, el worker por profundidad de cola, la api por peticiones por segundo.

Entra en `kit` lo que necesiten al menos dos ejecutables. Lo que use uno solo, vive en él.

**Regla verificable:** una prueba lee los fuentes del dominio y de los puertos y falla el build
si aparece un import de Spring, Kafka, Micrometer, el SDK de AWS o Jackson. Implementarla.

---

## 4. Flujo

```
plataforma → bus → consumer → cola de trabajo → worker → webhook del cliente
```

**Bus de entrada (Kafka).** Retención larga, varios lectores del mismo evento,
reprocesamiento histórico.

**Cola de trabajo (SQS).** La entrega no se hace sobre el bus: el paralelismo lo topan las
particiones y un webhook lento bloquearía a todos los clientes que compartan la suya. Además
SQS tiene retardo por mensaje, que es todo el backoff sin declarar colas de espera.

**Nadie empuja.** El consumer y el worker piden con *long polling*. Toda flecha en un diagrama
debe salir de quien inicia la llamada.

**El mensaje transporta solo el identificador.** El estado vive en la base. Un mensaje puede
esperar minutos en la cola; si transportara el estado, al procesarlo podría revertir un cambio
más reciente.

**Orden de operaciones en el worker, deliberado:** registrar el intento → actualizar el estado
→ encolar el reintento. Si el proceso muere a mitad, lo peor que pasa es que el broker
reentregue y se repita un intento —que la versión optimista detecta— en vez de perder el
rastro de una entrega que sí ocurrió.

---

## 5. Persistencia

DynamoDB. Dos tablas, porque nunca se leen juntas en una misma consulta y sus perfiles
difieren: los eventos crecen con el tráfico y caducan; las suscripciones crecen con el número
de clientes y no caducan.

### Tabla `notifications`

| `pk` | `sk` | Qué es |
|---|---|---|
| `EVENT#{event_id}` | `META` | Estado de la notificación |
| `EVENT#{event_id}` | `ATTEMPT#{reenvío:0000}#{intento:0000}` | Un intento de entrega |
| `STATS#{partición}` | `STATUS#{estado}` | Contador de backlog |

Índice `gsi_client_created`: partición `CLIENT#{client_id}`, orden `{created_at}#{event_id}`.
Solo lo llevan los ítems `META`.

Decisiones que deben respetarse:

- **`event_id` como partición** hace idempotente la ingesta: escritura condicionada a que la
  clave no exista.
- **Los intentos son ítems propios, no una lista dentro del evento.** El TTL expira ítems, no
  elementos de una lista; cada reenvío abre un ciclo nuevo, así que la lista no tendría cota
  frente al límite de 400 KB; y cada intento obligaría a reescribir el evento entero.
- **El número de reenvío va delante del de intento** en la clave de orden, porque cada reenvío
  reinicia el contador de intentos.
- **La fecha se guarda con ancho fijo.** `Instant.toString()` omite los decimales cuando son
  cero, y al comparar como texto `...:00.500Z` queda **antes** que `...:00Z`. Como ese texto es
  la clave de orden del índice, esa diferencia invierte el orden de la página.
- **El par `(attempts, replay_count)` es la versión optimista.** La actualización va
  condicionada a que siga igual.
- **Los contadores de backlog se reparten en varias particiones** y se actualizan en la misma
  transacción que el estado. Todas las transiciones del sistema escriben ahí: un único ítem
  concentraría cada escritura del flujo en una partición.

### Tabla `subscriptions`

| `pk` | `sk` |
|---|---|
| `CLIENT#{client_id}` | `SUB#{event_type}`, o `SUB#*` para todos los tipos |

La unicidad por tipo de evento sale de la clave de orden. El alta conserva el secreto existente
con `if_not_exists`: rotarlo en cada cambio de URL rompería la verificación de firma del cliente
sin avisarle.

### Lo que no se guarda

Credenciales de cliente. Las custodia un proveedor de identidad; ver §7.

---

## 6. API

```
POST /oauth/token                       flujo client_credentials
GET  /notification_events               listado con filtros y paginación por cursor
GET  /notification_events/{id}          detalle con la bitácora de intentos
POST /notification_events/{id}/replay   reenvío de una entrega fallida
POST /subscriptions                     registra el webhook y devuelve su secreto
GET  /subscriptions                     las suscripciones del cliente
```

**El `client_id` sale siempre del token.** No existe parámetro que permita expresar otro. El
modelo debe hacer imposible construir una consulta sin acotar por tenant: el objeto de consulta
exige el identificador de cliente en su constructor.

**Un recurso ajeno responde 404, no 403.** Un 403 confirmaría su existencia.

**Paginación por cursor**, sin número de página ni total. Saltar a la página N obliga a leer
las N anteriores, y contar exige recorrerlo todo. El cursor viaja opaco y **se comprueba que su
partición sea la del cliente autenticado**: sin eso, pasar el cursor de otro tenant es una vía
para leer sus notificaciones.

**El reenvío responde 202**, no 200: queda encolado. Solo se reenvía lo que está en `failed`.

---

## 7. Seguridad

Mínimo tres vulnerabilidades del OWASP Top 10 documentadas con su mitigación y su ubicación en
el código.

**Identidad delegada.** El servicio no emite tokens ni guarda credenciales: reenvía el flujo
`client_credentials` a un proveedor OIDC y valida las firmas contra su JWKS. El decodificador
se construye desde el emisor, para que se valide también el claim `iss` — sin eso, un token
bien firmado por cualquier otro proveedor sería aceptado.

El endpoint se conserva en la API para que quien integra tenga una sola URL y cambiar de
proveedor no le rompa nada.

**Firma de los webhooks.** HMAC-SHA256 sobre `timestamp + "." + cuerpo crudo`. El timestamp va
dentro de la firma para que una notificación válida no se pueda reenviar mañana. El secreto es
compartido por construcción: el cliente necesita el mismo valor para verificar.

**Anti-SSRF.** El destino lo elige el cliente. Rechazar lo que no sea HTTPS y las direcciones
internas, y **no seguir redirecciones**: un 302 puede reapuntar la petición a la red interna y
eludir la validación.

**Límite de tasa** por cliente autenticado, y uno mucho más estricto por dirección de origen en
el endpoint de token, donde lo que se contiene es la prueba de secretos a ciegas.

**Enmascarado de secretos en los logs**, aplicado antes de escribir la línea.

---

## 8. Política de reintentos

Cinco intentos. Escalones explícitos: 5s, 30s, 2m, 10m, 15m, con jitter del 20 %.

- **Ninguno puede superar los 15 minutos**: es el tope de retardo por mensaje de SQS.
- **Cada escalón supera el doble del anterior**, de modo que una espera con jitter nunca
  alcanza el siguiente.
- **Se reintenta solo lo que puede mejorar.** 5xx, 408, 429, timeouts y errores de conexión sí;
  un 400 o un 404 no, porque el problema está en el payload o en la ruta.
- **Un ciclo agotado no se publica en la cola muerta.** Se procesó hasta el final y quedó
  registrado como fallido, con su bitácora y su endpoint de reenvío. La cola muerta se reserva
  para lo que la aplicación no pudo procesar —mensajes corruptos, caídas a mitad—, y así su
  profundidad es una alarma que no suena en falso.
- **Sin suscripción activa el evento se descarta**, no se reintenta. Y conviene que eso deje
  señal: si el bus no debería traer eventos de clientes sin suscripción, un descarte es una
  anomalía del productor.

El retardo lo aplica el broker, no el proceso: una espera en memoria se pierde con el reinicio.

---

## 9. Entorno local

Todo levantable con `docker compose`, sin cuenta de AWS. La sustitución es **por protocolo, no
por proveedor**, de modo que el código sea el mismo que correría en la nube:

| Local | Producción | Diferencia en el código |
|---|---|---|
| Redpanda | Kafka gestionado | Ninguna |
| ElasticMQ | SQS | Ninguna |
| DynamoDB Local | DynamoDB | Ninguna: mismo SDK, otro endpoint |
| Keycloak | Cognito | Ninguna: los dos hablan OIDC |

Las tablas las declara la infraestructura. En local las crea la aplicación al arrancar, y esa
ruta debe venir apagada por defecto: un servicio con permiso para crear tablas lo tendría
también en producción, donde no lo va a usar nunca.

---

## 10. Criterios de aceptación

No basta con que compile. Debe comprobarse ejecutando:

- [ ] Un evento publicado en el bus llega al webhook y queda registrado con su intento.
- [ ] Un destino que responde 503 agota los reintentos con los escalones esperados y termina en
      la DLQ.
- [ ] Reenviar un evento fallido abre un ciclo nuevo: los intentos anteriores se conservan y
      los nuevos se ordenan después.
- [ ] El mismo evento publicado dos veces no se duplica ni dispara dos entregas.
- [ ] Un evento cuyo encolado falló se reencola en la siguiente ingesta, en vez de quedar
      colgado.
- [ ] Consultar un evento de otro cliente devuelve 404.
- [ ] El cursor de otro cliente se rechaza con 400.
- [ ] Un token sin el alcance requerido recibe 403.
- [ ] Los contadores de backlog coinciden con el estado real tras varias transiciones.
- [ ] Arranque en frío con los volúmenes borrados: las tablas se crean una sola vez aunque los
      tres ejecutables arranquen a la vez.

---

## 11. Limitaciones que conviene declarar

Un sistema honesto documenta lo que no resuelve:

- El secreto de firma se guarda en claro. El sitio correcto es un almacén de secretos, y eso
  obliga a una caché de vigencia corta: sin ella, cada intento sería una llamada facturada.
- La suscripción se lee en cada intento, incluidos los reintentos.
- No hay orden entre entregas del mismo cliente. Es deliberado —el orden global impide el
  paralelismo— pero debe decirse.
- La bitácora de intentos caduca por TTL; el estado final del evento, no.

---

## 12. Sobre el uso de esta especificación

Todo lo anterior es criterio, no código: qué construir, qué garantizar, qué rechazar y por qué.
Es la parte que una herramienta de IA no aporta — la produce quien sabe qué problema está
resolviendo.

Lo que sí aporta la herramienta es velocidad de ejecución y una segunda lectura del propio
razonamiento. Ambas cosas son útiles y ninguna sustituye a la de arriba.

Una advertencia de método, aprendida en la construcción de este repositorio: **nada se da por
bueno sin verlo correr**. Durante el desarrollo hubo diagnósticos equivocados sostenidos con
seguridad —incluido culpar al emulador de la cola de un mensaje que en realidad sí se había
entregado— y solo se resolvieron mirando el estado real del almacén. El apartado 10 existe por
eso.
