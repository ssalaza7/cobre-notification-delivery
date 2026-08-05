#!/usr/bin/env python3
"""Receptor de webhooks para la demostracion local.

Imprime lo que llega, verifica la firma HMAC y permite forzar codigos de respuesta
para ver el comportamiento de los reintentos sin depender de un servicio externo.

Uso:
    python3 scripts/webhook-receiver.py                 # responde 200 a todo
    python3 scripts/webhook-receiver.py --status 500    # falla siempre: dispara el backoff
    python3 scripts/webhook-receiver.py --fail-first 2  # falla los 2 primeros intentos y luego acepta

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
options = argparse.Namespace(status=200, fail_first=0)


def verify_signature(client_id: str, timestamp: str, signature: str, body: bytes) -> str:
    """Replica el procedimiento de verificacion que Cobre documenta para sus webhooks:
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
        status = options.status
        if options.fail_first and attempts_by_event[event_id] <= options.fail_first:
            status = 503

        verdict = verify_signature(
            client_id,
            self.headers.get("event-timestamp"),
            self.headers.get("event-signature"),
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


def main() -> None:
    parser = argparse.ArgumentParser(description="Receptor de webhooks de prueba")
    parser.add_argument("--port", type=int, default=9090)
    parser.add_argument("--status", type=int, default=200, help="Codigo a devolver siempre")
    parser.add_argument("--fail-first", type=int, default=0,
                        help="Responde 503 los N primeros intentos de cada evento y luego 200")
    parser.parse_args(namespace=options)

    print(f"Receptor de webhooks escuchando en http://localhost:{options.port}/webhooks/<CLIENT_ID>",
          flush=True)
    HTTPServer(("0.0.0.0", options.port), WebhookHandler).serve_forever()


if __name__ == "__main__":
    main()
