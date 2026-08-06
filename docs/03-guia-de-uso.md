# Guía de uso

Lo operativo. El [README](../README.md) cubre la arquitectura y los escenarios; aquí está el
detalle de cómo se maneja el servicio.

- [Registrar webhooks](#registrar-webhooks)
- [Tokens](#tokens)
- [El receptor de pruebas](#el-receptor-de-pruebas)
- [Colección de Postman](#colección-de-postman)
- [Las consolas](#las-consolas)
- [Aislamiento entre clientes](#aislamiento-entre-clientes)

---

## Registrar webhooks

```bash
./scripts/register-webhook.sh CLIENT001 https://mi-sistema.com/webhooks
```

```
Webhook registrado.
  cliente : CLIENT001
  eventos : *
  destino : https://mi-sistema.com/webhooks

  Secreto de firma (se muestra una sola vez):
    whsec_H6foh8fFGCR1AAVdbUFvo2J3KAzFjHDFGCF6Dmcq
```

El secreto **se muestra una sola vez**. Con él el cliente verifica la cabecera
`X-Cobre-Signature` de cada notificación: es lo que le permite saber que el mensaje viene de
nosotros y no de alguien que descubrió su URL.

### Ver, cambiar y dar de baja

```bash
./scripts/register-webhook.sh --list CLIENT001
./scripts/register-webhook.sh CLIENT001 https://nuevo-dominio.com/hooks   # cambia el destino
./scripts/register-webhook.sh --off CLIENT001 '*'
```

Cambiar la URL **no rota el secreto**: hacerlo rompería la verificación del cliente sin
avisarle. La baja no borra la fila, la desactiva — la bitácora de lo ya entregado apunta a
ella.

Todo aplica desde la siguiente notificación. No hay que reiniciar nada.

### Un destino por tipo de evento

El esquema admite una suscripción por `event_type`, más un comodín `*` que recoge el resto.
Sirve para que un cliente mande cada tipo a un sistema distinto:

```bash
./scripts/register-webhook.sh CLIENT001 https://pagos.mi-sistema.com/hooks   credit_transfer
./scripts/register-webhook.sh CLIENT001 https://alertas.mi-sistema.com/hooks balance_updated
```

Al entregar gana el tipo específico sobre el comodín.

### Redirigir todo sin tocar la base

```bash
WEBHOOK_OVERRIDE_URL=https://el-destino/webhook \
java -jar build/libs/notification-delivery-service-0.0.1-SNAPSHOT.jar
```

Tiene precedencia sobre lo que haya en la base. Útil cuando la URL de prueba se conoce el
mismo día; arranca en 3 segundos.

### Qué se valida

Sin el perfil `local`, la validación va en modo estricto: **se exige HTTPS** y se rechazan
destinos que resuelvan a la red interna (`169.254.169.254`, rangos privados, loopback). Sin
eso el servicio sería un proxy: cualquiera podría registrar una URL interna y conseguir que
Cobre haga esa petición desde dentro de su red. Es SSRF, y aquí importa especialmente porque
**la URL destino la elige el cliente**.

Verificado contra un endpoint HTTPS público real (`postman-echo.com`): entregado en 1
intento. El mismo destino en `http://`: `failed` en 1 intento, *"El webhook debe usar
HTTPS"*.

---

## Tokens

Acepta los dos formatos de cuerpo. El primero es el del estándar:

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

| client_id | client_secret | scopes |
|---|---|---|
| `CLIENT001` | `demo-secret-client001` | read · replay · monitor |
| `CLIENT002` | `demo-secret-client002` | read · replay · monitor |
| `CLIENT003` | `demo-secret-client003` | **solo read** |

`CLIENT003` no puede reenviar a propósito: consultar y reenviar son autorizaciones distintas.

### Por qué POST y no GET

Un `GET` llevaría el `client_secret` en la URL, y las URLs no se quedan donde uno cree: van
al log de acceso del balanceador, al historial del navegador, a la cabecera `Referer` y a
cualquier proxy intermedio — todos fuera de nuestro control. Es además lo que exige el
RFC 6749 para `client_credentials`, y la forma en que lo piden las pasarelas del mercado.

### Los logs enmascaran

Que nosotros no pongamos secretos en la URL no impide que un cliente lo haga por error al
integrar. El log de acceso tapa el valor de cualquier parámetro sensible antes de escribirlo:

```
GET /notification_events?size=1&client_secret=***&access_token=***
```

Se conserva el **nombre** del parámetro a propósito: saber que alguien mandó un
`client_secret` por la URL es justo lo que permite avisarle de que corrija la integración. Un
secreto que llega al índice no se puede "desfiltrar": queda replicado en cada backup y obliga
a rotarlo.

---

## El receptor de pruebas

`scripts/webhook-receiver.py` **hace de cliente**: recibe la notificación, verifica la firma
y responde. No es parte del sistema — simula el sistema del cliente, que es quien de verdad
decide si acepta o rechaza.

Hay que poder mostrar varios comportamientos —entrega limpia, reintentos, fallo definitivo,
timeout— y reiniciar el receptor con otra bandera entre uno y otro corta el hilo de una
presentación. Por eso **el receptor mira el identificador del evento y decide cómo
responder**. Con una sola instancia corriendo, el escenario se elige al publicar:

| Si publicas | El receptor responde | Qué demuestra |
|---|---|---|
| `EVT-DEMO-1` | 200 | Entrega exitosa a la primera |
| `EVT-RECUPERA-1` | 503, después 200 | El backoff recupera la entrega |
| `EVT-FALLA-1` | 503 siempre | Se agotan los reintentos: queda `failed` y va a la DLQ |
| `EVT-RECHAZA-1` | 400 | Contrato roto: **no se reintenta** |
| `EVT-LENTO-1` | no contesta a tiempo | Timeout: el destino ni respondió |

Es una convención **del receptor**, no del servicio. El servicio trata todos los eventos
igual; el que decide es el destino.

---

## Colección de Postman

En [postman/](../postman/), tres carpetas y siete peticiones. Solo lo que un cliente hace de
verdad.

| Carpeta | Peticiones | Qué demuestra |
|---|---|---|
| **1 · Flujo exitoso** | 1 | Publicar el evento. **Es lo único que se hace**: el resto es autónomo |
| **2 · Flujo con reintento** | 1 | Publicar un evento cuyo destino rechaza. Los reintentos aparecen solos |
| **3 · API self-service** | 5 | Token, listado, detalle, reenvío, detalle otra vez |

Las peticiones encadenan variables: el token se guarda al obtenerlo y el id de la
notificación fallida se captura del listado.

---

## Las consolas

```bash
docker compose --profile observability up -d
```

| Consola | URL |
|---|---|
| **Grafana** — el tablero ya viene cargado | http://localhost:3000 |
| **Kibana** — las vistas se cargan con `./scripts/kibana-import.sh` | http://localhost:5601 |
| **SQS** — la cola de entrega y la DLQ | http://localhost:9324 |
| **Prometheus** — las métricas en crudo | http://localhost:9091 |

Las vistas de Kibana están versionadas en
[`observability/kibana/vistas.ndjson`](../observability/kibana/vistas.ndjson): se cargan con
un comando y no se pierden al recrear el contenedor. Quedan cinco en *Discover → Open*: todo
el tráfico · entregas · llamadas a la API · solo errores · traza de un evento.

> Regenerar las imágenes del README necesita además el renderizador, que está en su propio
> perfil (`--profile evidencia`). Levanta un Chromium con picos de memoria fuertes, y no
> hace falta para una demostración.

### Cómo llega el dato a cada consola

La aplicación **no le envía métricas a nadie**: las publica en `/actuator/prometheus`, que es
una foto del instante. Prometheus **va y consulta ese endpoint cada 5 segundos, y guarda cada
lectura** —como un lector de medidor que pasa a anotar la cifra, en vez de que el medidor lo
llame—. Grafana no habla con la aplicación: le pregunta a Prometheus, que tiene el histórico.

Con los logs es al revés en el último tramo: la aplicación escribe JSON, **Filebeat lo lee y
lo envía** a Elasticsearch, y Kibana consulta ahí.

En los dos casos **la aplicación no conoce el destino final**. Por eso cambiar Prometheus por
Datadog no toca una línea de código: el agente de Datadog consulta el mismo endpoint. Está
cableado y apagado por defecto porque necesita cuenta y API key.

### Los paneles

| Panel | Para qué sirve |
|---|---|
| Entregadas · Fallidas | El resultado neto |
| **Reintentos exitosos** | Las que fallaron y se recuperaron solas. Sin reintentos, perdidas |
| **Reintentos agotados** | Las que fallaron hasta el final. Requieren intervención |
| Reenvíos manuales | Si crece, algo estructural está roto |
| **Errores por código** | Un 5xx es transitorio; un 4xx es contrato roto |
| **Latencia del webhook** | p50 · p95 · p99. Lo primero que se degrada, antes de los timeouts |
| A la primera vs. recuperadas | Si las recuperadas crecen, los destinos se degradan aunque el resultado siga bien |
| **Clientes con entregas fallando** | **Qué** cliente se cayó, no solo cuántas fallaron |
| **Sin respuesta del cliente** | Timeouts y conexiones rechazadas: el destino ni contestó |
| Tiempo de respuesta promedio | La tendencia general, al lado de los percentiles |

### Sobre `client_id` como etiqueta

**Solo una métrica lo lleva**, `cobre_notification_client_failures_total`, y es deliberado en
los dos sentidos.

Etiquetar *todas* las métricas por cliente multiplica las series de tiempo por el número de
clientes. Con miles, eso tumba a Prometheus, y en Datadog cada combinación se factura.

Pero no tenerlo en *ninguna* deja a guardia sin poder responder la primera pregunta de un
incidente: **¿qué cliente se cayó?** Habría que ir a los logs, más lento justo cuando el
tiempo importa, y no se podría alertar automáticamente.

La salida es acotarlo al fallo. **La serie solo nace cuando un cliente falla**, así que la
cota no son todos los clientes: son los que están fallando ahora, que en un sistema sano son
unos pocos. Con eso se monta la alerta que importa —*"CLIENT002 lleva 5 minutos fallando"*— y
guardia sabe a quién llamar sin abrir Kibana.

---

## Aislamiento entre clientes

`EVT005` es de `CLIENT003`. Con un token de `CLIENT002`:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/notification_events/EVT005
# 404
```

**404 y no 403.** Un 403 confirmaría que el recurso existe y permitiría enumerar
identificadores ajenos. Hacia afuera, "no existe" y "no es tuyo" son indistinguibles.

El `client_id` sale siempre del token, nunca de la ruta ni de la query: no hay ningún
parámetro que permita expresar "los datos de otro".
