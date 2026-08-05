#!/usr/bin/env bash
# Publica un evento en el bus de la plataforma, simulando lo que haria otro
# microservicio de Cobre. Usa la API de gestion de RabbitMQ para no depender de
# ninguna libreria de cliente.
#
# Uso:
#   ./scripts/publish-event.sh                                   # evento de ejemplo
#   ./scripts/publish-event.sh EVT100 CLIENT001 credit_transfer "Transferencia por $250.000"

set -euo pipefail

EVENT_ID="${1:-EVT-$(date +%s)}"
CLIENT_ID="${2:-CLIENT001}"
EVENT_TYPE="${3:-credit_transfer}"
CONTENT="${4:-Transferencia recibida por \$1.500.000}"
CREATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

RABBIT_URL="${RABBIT_URL:-http://localhost:15672}"
RABBIT_USER="${RABBIT_USER:-guest}"
RABBIT_PASSWORD="${RABBIT_PASSWORD:-guest}"
EXCHANGE="cobre.platform.events"

PAYLOAD=$(cat <<JSON
{"event_id":"${EVENT_ID}","client_id":"${CLIENT_ID}","event_type":"${EVENT_TYPE}","content":"${CONTENT}","created_at":"${CREATED_AT}"}
JSON
)

BODY=$(python3 - "$PAYLOAD" <<'PY'
import json, sys
print(json.dumps({
    "properties": {"content_type": "application/json", "delivery_mode": 2},
    "routing_key": "notification.created",
    "payload": sys.argv[1],
    "payload_encoding": "string",
}))
PY
)

echo "Publicando en ${EXCHANGE}: ${PAYLOAD}"
curl -s -u "${RABBIT_USER}:${RABBIT_PASSWORD}" \
  -H "Content-Type: application/json" \
  -X POST "${RABBIT_URL}/api/exchanges/%2F/${EXCHANGE}/publish" \
  -d "${BODY}"
echo
