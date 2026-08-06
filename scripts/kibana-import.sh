#!/usr/bin/env bash
#
# Carga en Kibana la vista de datos y las busquedas guardadas del servicio.
#
# Existen como archivo versionado y no como clics en la interfaz porque los objetos
# guardados viven dentro del contenedor: al recrearlo se pierden. Asi se recuperan
# con un comando y todos ven lo mismo.
set -euo pipefail

KIBANA="${KIBANA_URL:-http://localhost:5601}"
ARCHIVO="$(dirname "$0")/../observability/kibana/vistas.ndjson"

echo "Cargando vistas en ${KIBANA}..."
RESPUESTA=$(curl -s --max-time 60 -X POST "${KIBANA}/api/saved_objects/_import?overwrite=true" \
  -H "kbn-xsrf: true" -F "file=@${ARCHIVO}")

python3 - "$RESPUESTA" <<'PY'
import json, sys
r = json.loads(sys.argv[1])
if not r.get("success"):
    print("Fallo la carga:", r.get("message") or r.get("errors"))
    sys.exit(1)
print(f"{r['successCount']} objetos cargados:")
for o in r["successResults"]:
    print(f"  - {o['meta']['title']}")
PY

echo
echo "Abre Discover -> Open y elige una vista:"
echo "  ${KIBANA}/app/discover"
