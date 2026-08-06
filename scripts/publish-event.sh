#!/usr/bin/env bash
#
# Publica un evento en el topic de la plataforma, simulando lo que hace otro servicio
# de Cobre. Usa el proxy HTTP de Redpanda para no depender de un cliente de Kafka.
#
# Uso:
#   ./scripts/publish-event.sh EVT-DEMO-1 CLIENT001 credit_transfer "Transferencia por 1.500.000"
#
# El identificador decide como responde el receptor de pruebas:
#   *FALLA*    -> rechaza los 3 intentos y agota el ciclo
#   *RECUPERA* -> rechaza 1 y luego acepta
#   cualquier otro -> se acepta a la primera
set -euo pipefail

EVENT_ID="${1:-EVT-DEMO-$(date +%s)}"
CLIENT_ID="${2:-CLIENT001}"
EVENT_TYPE="${3:-credit_transfer}"
CONTENT="${4:-Transferencia recibida}"
PROXY="${KAFKA_HTTP_URL:-http://localhost:8082}"
TOPIC="${KAFKA_TOPIC:-cobre.platform.events}"

CUERPO=$(python3 - "$EVENT_ID" "$CLIENT_ID" "$EVENT_TYPE" "$CONTENT" <<'PY'
import datetime, json, sys
evento = {
    "event_id": sys.argv[1],
    "client_id": sys.argv[2],
    "event_type": sys.argv[3],
    "content": sys.argv[4],
    "created_at": datetime.datetime.now(datetime.UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
}
# El proxy espera el evento como valor del registro, no como cadena.
print(json.dumps({"records": [{"value": evento}]}))
PY
)

echo "Publicando en el topic ${TOPIC}: ${EVENT_ID}"
curl -s -X POST "${PROXY}/topics/${TOPIC}" \
  -H "Content-Type: application/vnd.kafka.json.v2+json" \
  -d "${CUERPO}"
echo
