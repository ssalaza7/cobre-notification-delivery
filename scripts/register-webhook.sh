#!/usr/bin/env bash
#
# Registra el webhook de un cliente.
#
# Un cliente puede tener VARIOS: uno por tipo de evento, mas un comodin '*' que recoge
# todo lo demas. Asi puede mandar las transferencias a un sistema y las alertas de saldo
# a otro sin duplicar configuracion. Al entregar gana el tipo especifico sobre el comodin.
#
# Uso:
#   ./scripts/register-webhook.sh CLIENT001 https://destino/webhook
#   ./scripts/register-webhook.sh CLIENT001 https://destino/pagos  credit_transfer
#   ./scripts/register-webhook.sh --list  CLIENT001
#   ./scripts/register-webhook.sh --off   CLIENT001 credit_transfer
#
# El secreto de firma se genera aqui y se muestra UNA sola vez, al crearlo. Actualizar
# la URL no lo rota: si se rotara en cada cambio, la verificacion de firma del cliente
# se romperia sin avisarle.
#
set -euo pipefail

CONTENEDOR="${PG_CONTAINER:-cobre-notifications-postgres}"
BD="${DB_NAME:-notifications}"
USUARIO="${DB_USER:-cobre}"

# La interpolacion de :'variable' solo ocurre cuando el SQL llega por stdin, no con -c.
# Se usa asi a proposito: es lo que escapa las comillas y evita inyeccion en un script
# que recibe una URL por linea de comandos.
psql_() { docker exec -i "$CONTENEDOR" psql -U "$USUARIO" -d "$BD" "$@"; }

uso() {
  sed -n '3,18p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-1}"
}

listar() {
  local cliente="${1:-}"
  local filtro="TRUE"
  [ -n "$cliente" ] && filtro="client_id = :'cliente'"
  psql_ -v cliente="${cliente:-}" <<SQL
    SELECT client_id AS cliente,
           event_type AS tipo_de_evento,
           webhook_url AS destino,
           CASE WHEN active THEN 'si' ELSE 'no' END AS activo
    FROM subscription
    WHERE $filtro
    ORDER BY client_id, (event_type = '*'), event_type;
SQL
}

desactivar() {
  local cliente="$1" tipo="$2"
  local afectadas
  afectadas=$(psql_ -tAq -v cliente="$cliente" -v tipo="$tipo" <<'SQL'
    WITH baja AS (
      UPDATE subscription SET active = FALSE
      WHERE client_id = :'cliente' AND event_type = :'tipo' AND active
      RETURNING 1
    ) SELECT count(*) FROM baja;
SQL
)

  if [ "$afectadas" = "0" ]; then
    echo "No habia un webhook activo de $cliente para '$tipo'."
    exit 1
  fi
  # No se borra la fila: la bitacora de lo ya entregado apunta a ella.
  echo "Webhook de $cliente para '$tipo' desactivado. Deja de recibir desde la proxima notificacion."
}

registrar() {
  local cliente="$1" url="$2" tipo="${3:-*}"

  case "$url" in
    https://*) ;;
    http://*)  echo "Aviso: '$url' no es HTTPS. Solo funciona con el perfil 'local';" \
                    "en 'demo' y en produccion se rechaza como fallo permanente." ;;
    *) echo "La URL debe empezar por http:// o https://"; exit 1 ;;
  esac

  local secreto
  secreto="whsec_$(openssl rand -base64 32 | tr -d '=+/' | cut -c1-40)"

  # xmax = 0 distingue el INSERT del UPDATE: sirve para mostrar el secreto solo cuando
  # la suscripcion es nueva.
  local resultado
  resultado=$(psql_ -tAq -v cliente="$cliente" -v url="$url" -v tipo="$tipo" -v secreto="$secreto" <<'SQL'
    INSERT INTO subscription (id, client_id, event_type, webhook_url, signing_secret, active)
    VALUES (gen_random_uuid(), :'cliente', :'tipo', :'url', :'secreto', TRUE)
    ON CONFLICT (client_id, event_type) WHERE active
    DO UPDATE SET webhook_url = EXCLUDED.webhook_url
    RETURNING CASE WHEN xmax = 0 THEN 'nuevo' ELSE 'actualizado' END, signing_secret;
SQL
)

  local estado
  estado="${resultado%%|*}"

  echo
  if [ "$estado" = "nuevo" ]; then
    echo "Webhook registrado."
    echo "  cliente : $cliente"
    echo "  eventos : $tipo"
    echo "  destino : $url"
    echo
    echo "  Secreto de firma (se muestra una sola vez):"
    echo "    ${resultado##*|}"
    echo
    echo "  Con el se verifica la cabecera X-Cobre-Signature de cada notificacion."
  else
    echo "Webhook actualizado."
    echo "  cliente : $cliente"
    echo "  eventos : $tipo"
    echo "  destino : $url"
    echo "  El secreto de firma no cambia."
  fi
  echo
  echo "Aplica desde la proxima notificacion: no hay que reiniciar nada."
}

case "${1:-}" in
  ""|-h|--help) uso 0 ;;
  --list) listar "${2:-}" ;;
  --off)
    [ $# -ge 3 ] || uso
    desactivar "$2" "$3" ;;
  *)
    [ $# -ge 2 ] || uso
    registrar "$1" "$2" "${3:-*}"
    echo
    listar "$1" ;;
esac
