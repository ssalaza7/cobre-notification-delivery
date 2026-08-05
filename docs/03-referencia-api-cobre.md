# Referencia: cómo lo hace Cobre hoy

Antes de cerrar decisiones revisé la documentación pública de Cobre para no diseñar en
el vacío. Este documento resume qué encontré, qué adopté, qué **no** adopté y dónde
esta propuesta se aparta a propósito.

> **Fuente y alcance.** Documentación pública en [docs.cobre.com](https://docs.cobre.com),
> consultada el 5 de agosto de 2026, más el sitio corporativo y descripciones de
> vacantes. Es información pública y puede estar desactualizada respecto a lo que corre
> hoy en producción. **Este ejercicio no pretende replicar la API de Cobre**: toma sus
> conceptos como referencia de realidad y se aparta donde el ejercicio lo pide.

---

## 1. Cobre ya tiene una API de notificaciones

La documentación describe **Notifications & Subscriptions**: un mecanismo centralizado
para suscribirse a eventos de la plataforma y recibirlos por webhook. Cubre cuentas,
movimientos de dinero, operaciones transfronterizas, operaciones masivas, reportes,
solicitudes de evidencia y Cobre Keys.

Es decir: **el enunciado no es hipotético**. Pide rediseñar una capacidad que existe.
Eso cambia cómo conviene presentar la solución — no como una idea desde cero, sino como
una evolución con criterios explícitos.

### Modelo de suscripción

```
POST /v1/subscriptions
  events               → arreglo de claves de evento
  url                  → destino del webhook
  description          → opcional
  event_signature_key  → opcional, habilita notificaciones firmadas
```

La respuesta enmascara `event_signature_key` dejando visibles solo los últimos 4
caracteres.

### Claves de evento jerárquicas

El formato es `[servicio].[tipo].[estado]`:

```
accounts.balance.credit
money_movements.status.completed
cross_border_money_movements.status.rejected
cobre_keys.status.registered
```

### Estructura del evento

```json
{
  "id": "ev_xxxxx",
  "event_key": "[service].[type].[status]",
  "created_at": "ISO-8601",
  "content": { }
}
```

---

## 2. Lo que adopté

### Esquema de firma (implementado)

| Aspecto | Convención de Cobre | En este repo |
|---|---|---|
| Contenido firmado | `timestamp + "." + cuerpo crudo` | Idéntico |
| Algoritmo | HMAC-SHA256, UTF-8 | Idéntico |
| Cabecera del hash | `event-signature` | Idéntico |
| Cabecera del instante | `event-timestamp` | Idéntico |

Esto no es cosmético. Un cliente que ya integró webhooks de Cobre **no debería tener
que escribir un verificador distinto** para estos. La compatibilidad del esquema de
firma es lo que hace que la migración a una implementación nueva sea invisible para
quien la consume.

📁 `WebhookSigner`, y `scripts/webhook-receiver.py` implementa la verificación tal como
la haría un cliente.

### Confirmación de decisiones de infraestructura

La documentación pide poner en lista blanca las IPs de salida de Cobre
(`50.17.12.196`, `54.173.144.191`). Dos conclusiones:

1. **Confirma AWS** — ambas están en rangos EC2 de `us-east-1`.
2. **Valida el diseño de egreso**: NAT Gateway con Elastic IPs fijas. Los clientes
   empresariales necesitan IPs de origen estables en sus firewalls; sin ellas, cada
   reescalado rompería integraciones. No era una preferencia mía, es un requisito que
   Cobre ya vive.

---

## 3. Lo que no adopté, y por qué

Es una prueba técnica, no un clon. Estas convenciones las conozco y las respetaría en
una integración real, pero replicarlas aquí habría añadido ruido sin demostrar nada:

| Convención de Cobre | Aquí | Razón |
|---|---|---|
| Paginación `page_number` / `page_size` / `contents` / `is_last_page` | `page` / `size` / `data` / `has_next` | El enunciado no lo especifica; en una integración real adoptaría la suya por consistencia |
| Claves jerárquicas `[servicio].[tipo].[estado]` | `event_type` plano | El archivo `notification_events.json` entregado usa `credit_card_payment`, y ese archivo es el requisito |
| `content` como objeto | `content` como texto | Igual: el archivo de la prueba lo trae como cadena |
| Rate limit 30 TPS con ráfaga de 200 (token bucket) | Ventana fija, 120/min | Simplificación consciente; el límite real pertenece al borde (WAF), no a la aplicación |
| Idempotencia por cabecera en POST | Bloqueo optimista en el reenvío | El reenvío ya es idempotente por diseño |

---

## 4. Donde esta propuesta va más lejos: la estrategia de reintentos

Aquí está el hallazgo más interesante, y creo que es justo lo que el enunciado busca al
pedir *"handling error with an efficient retry strategy"*.

### Lo documentado hoy

| Aspecto | Política actual |
|---|---|
| Intentos | 3 reintentos |
| Esperas | 200 ms · 400 ms · 1000 ms |
| Se reintenta | **Solo errores de conexión** |
| No se reintenta | Respuestas 4xx |
| Ventana total | **~1,6 segundos** |

### El problema

**Una ventana de 1,6 segundos no sobrevive a nada real.** Cuando un cliente despliega
una versión nueva, su endpoint queda no disponible entre 30 segundos y varios minutos.
Los tres reintentos se agotan en el primer segundo y medio, y la notificación se pierde.

Y como solo se reintentan errores de conexión, un cliente que responde `503 Service
Unavailable` durante su despliegue —que es lo correcto y lo que hace cualquier
balanceador— **no obtiene ningún reintento**. El caso más común de indisponibilidad
temporal es precisamente el que no está cubierto.

### Lo que propone esta implementación

| Aspecto | Propuesta |
|---|---|
| Intentos | 5 |
| Esperas | 5s · 30s · 2m · 10m · 30m, con jitter del 20% |
| Se reintenta | 5xx, 408, 425, 429, timeouts y errores de conexión |
| No se reintenta | 4xx de contrato y redirecciones |
| Ventana total | **~43 minutos** |
| Al agotarse | Estado `failed` + DLQ + endpoint de reenvío manual |

**Por qué 43 minutos y no más:** cubre con holgura un despliegue, un reinicio o una
degradación pasajera. Más allá de eso el problema deja de ser transitorio y pasa a
necesitar intervención humana — que es exactamente para lo que existe
`POST /notification_events/{id}/replay`.

**Por qué el jitter:** si el webhook de un cliente se cae un minuto, todas sus
notificaciones fallan a la vez. Sin jitter reintentarían todas en el mismo instante,
tumbándolo de nuevo justo cuando se estaba recuperando.

**Por qué distinguir 4xx de 5xx:** un 400 significa que el payload está mal y
reintentarlo mil veces no lo arregla; un 503 significa "vuelve más tarde" y no
reintentarlo pierde una notificación por una caída de segundos.

---

## 5. Otras convenciones observadas

Recogidas para el contexto de la conversación con el panel:

- **Montos** en la unidad mínima de la moneda (centavos). Evita errores de punto
  flotante — la razón por la que toda plataforma de pagos seria lo hace así.
- **Marcas de tiempo** siempre en UTC ISO-8601. Coincide con el diseño aquí.
- **Idempotencia obligatoria** en POST de movimientos de dinero, mínimo 9 caracteres.
- **Tope de paginación** de 10.000 registros. Confirma el criterio de acotar `size`:
  ninguna API pública debe permitir descargar una tabla completa.
- **Rotación mensual de claves de API.** Refuerza la recomendación de migrar de HS256
  con secreto compartido a JWKS contra el IdP.
- **Certificaciones**: ISO 27001:2022, SOC 2 Type II, PCI DSS v4.0.1, regulados por la
  Superintendencia Financiera. Es el contexto que justifica no registrar el `content`
  de las notificaciones y elegir cómputo sin nodos que administrar.

---

## Fuentes

- [Documentación Cobre](https://docs.cobre.com)
- [Notifications & Subscriptions](https://docs.cobre.com/notifications-subscriptions-1886572m0)
- [Inicio rápido](https://docs.cobre.com/es/inicio-r%C3%A1pido-1952100m0)
- [Cobre — sitio corporativo](https://www.cobre.com/es-co)
