#!/usr/bin/env bash
#
# Cambia el destino de las suscripciones sin reiniciar el servicio.
#
# Pensado para la presentacion: la URL destino se entrega ese mismo dia, y reiniciar la
# aplicacion delante de una audiencia son cincuenta segundos de silencio. El adaptador
# de suscripciones consulta la base en cada entrega, asi que un UPDATE surte efecto de
# inmediato en la siguiente notificacion.
#
# Uso:
#   ./scripts/set-webhook-url.sh https://el-destino-que-me-dieron/webhook
#   ./scripts/set-webhook-url.sh https://destino/webhook CLIENT001   # solo un cliente
#
set -euo pipefail

URL="${1:-}"
CLIENTE="${2:-}"
CONTENEDOR="${PG_CONTAINER:-cobre-notifications-postgres}"
BD="${DB_NAME:-notifications}"
USUARIO="${DB_USER:-cobre}"

if [ -z "$URL" ]; then
  echo "Falta la URL destino."
  echo "Uso: $0 <url> [client_id]"
  exit 1
fi

# Aviso temprano: con el perfil `demo` o el de produccion, un destino que no sea HTTPS
# se rechaza como fallo permanente y no llega a intentarse siquiera.
case "$URL" in
  https://*) ;;
  *) echo "Aviso: '$URL' no es HTTPS. Solo funcionara con el perfil 'local'." ;;
esac

if [ -n "$CLIENTE" ]; then
  FILTRO="WHERE client_id = '$CLIENTE'"
  ALCANCE="del cliente $CLIENTE"
else
  FILTRO=""
  ALCANCE="de todos los clientes"
fi

docker exec -i "$CONTENEDOR" psql -U "$USUARIO" -d "$BD" -q \
  -c "UPDATE subscription SET webhook_url = '$URL' $FILTRO;"

echo "Destino $ALCANCE actualizado a: $URL"
echo
docker exec -i "$CONTENEDOR" psql -U "$USUARIO" -d "$BD" \
  -c "SELECT client_id, webhook_url FROM subscription ORDER BY client_id;"
