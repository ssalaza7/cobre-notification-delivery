#!/usr/bin/env python3
"""Receptor de webhooks para la demostracion local.

Imprime lo que llega, verifica la firma HMAC y permite forzar codigos de respuesta
para ver el comportamiento de los reintentos sin depender de un servicio externo.

Por defecto responde 200, salvo que el identificador del evento contenga "FALLA": esos
fallan los primeros intentos y despues aceptan. Asi una misma instancia sirve para
demostrar la entrega exitosa y el ciclo de reintentos sin reiniciar nada a mitad de una
presentacion.

Uso:
    python3 scripts/webhook-receiver.py                 # 200, salvo eventos con FALLA
    python3 scripts/webhook-receiver.py --status 500    # falla siempre: agota los reintentos
    python3 scripts/webhook-receiver.py --fail-first 2  # falla los 2 primeros de CUALQUIER evento

Las URLs sembradas apuntan a http://localhost:9090/webhooks/<CLIENT_ID>.
"""

import argparse
import hashlib
import hmac
import json
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, HTTPServer

SECRETS = {
    "CLIENT001": "whsec_client001_local_dev_secret",
    "CLIENT002": "whsec_client002_local_dev_secret",
    "CLIENT003": "whsec_client003_local_dev_secret",
}

attempts_by_event = defaultdict(int)
options = argparse.Namespace(status=200, fail_first=0, fail_pattern="FALLA", fail_times=3)


def verify_signature(client_id: str, timestamp: str, signature: str, body: bytes) -> str:
    """Verificacion que haria el cliente en su extremo:
    HMAC-SHA256 sobre `timestamp + "." + cuerpo crudo`, en UTF-8."""
    secret = SECRETS.get(client_id)
    if not secret or not signature or not timestamp:
        return "sin verificar"
    signed = f"{timestamp}.{body.decode()}".encode()
    expected = hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest()
    return "FIRMA OK" if hmac.compare_digest(expected, signature) else "FIRMA INVALIDA"


class WebhookHandler(BaseHTTPRequestHandler):

    def do_POST(self):  # noqa: N802  (nombre impuesto por BaseHTTPRequestHandler)
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        event_id = self.headers.get("X-Cobre-Event-Id", "?")
        attempt = self.headers.get("X-Cobre-Delivery-Attempt", "?")
        client_id = self.path.rsplit("/", 1)[-1]

        attempts_by_event[event_id] += 1
        status = decide_status(event_id, attempts_by_event[event_id])

        verdict = verify_signature(
            client_id,
            self.headers.get("X-Cobre-Timestamp"),
            self.headers.get("X-Cobre-Signature"),
            body)
        try:
            payload = json.dumps(json.loads(body), ensure_ascii=False)
        except json.JSONDecodeError:
            payload = body.decode(errors="replace")

        # flush explicito: redirigido a un archivo, stdout se almacena en bufer y la
        # traza de la demo no aparece hasta que el proceso termina.
        print(f"[{event_id}] intento {attempt} -> respondo {status} | {verdict}", flush=True)
        print(f"    {payload}", flush=True)

        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"received":true}')

    def log_message(self, *_):
        """Silencia el log por defecto: ya se imprime lo relevante."""


def decide_status(event_id: str, intento: int) -> int:
    """Decide que responder segun el evento y el numero de intento.

    Un evento cuyo identificador contiene el patron de fallo devuelve 503 en sus
    primeros intentos y 200 despues. Eso permite mostrar en la misma sesion una entrega
    limpia y un ciclo de reintentos, sin tocar el receptor entre una y otra.
    """
    if options.fail_first and intento <= options.fail_first:
        return 503
    if options.fail_pattern and options.fail_pattern.upper() in event_id.upper():
        return 503 if intento <= options.fail_times else 200
    return options.status


def main() -> None:
    parser = argparse.ArgumentParser(description="Receptor de webhooks de prueba")
    parser.add_argument("--port", type=int, default=9090)
    parser.add_argument("--status", type=int, default=200, help="Codigo a devolver siempre")
    parser.add_argument("--fail-first", type=int, default=0,
                        help="Responde 503 los N primeros intentos de CUALQUIER evento")
    parser.add_argument("--fail-pattern", default="FALLA",
                        help="Los eventos cuyo id contenga este texto fallan sus primeros intentos")
    parser.add_argument("--fail-times", type=int, default=3,
                        help="Cuantos intentos fallan los eventos que coinciden con el patron. Por defecto 3, que es lo que agota el ciclo en el perfil local: asi el evento queda fallido y el reenvio manual -que abre un ciclo nuevo- si se entrega.")
    parser.parse_args(namespace=options)

    print(f"Receptor de webhooks escuchando en http://localhost:{options.port}/webhooks/<CLIENT_ID>",
          flush=True)
    if options.fail_pattern:
        print(f"  Los eventos con '{options.fail_pattern}' en el id fallaran sus primeros "
              f"{options.fail_times} intentos; el resto se aceptan.", flush=True)
    HTTPServer(("0.0.0.0", options.port), WebhookHandler).serve_forever()


if __name__ == "__main__":
    main()
