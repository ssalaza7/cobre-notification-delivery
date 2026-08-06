#!/usr/bin/env python3
"""Receptor de webhooks para la demostracion local.

Imprime lo que llega, verifica la firma HMAC y permite forzar codigos de respuesta
para ver el comportamiento de los reintentos sin depender de un servicio externo.

Por defecto responde 200.

PARA QUE SIRVEN LOS PATRONES DEL IDENTIFICADOR
Hace falta demostrar varios comportamientos —entrega limpia, reintentos, fallo definitivo,
timeout— y reiniciar el receptor con otra bandera entre uno y otro corta el hilo de una
presentacion. La solucion es que el receptor mire el identificador del evento y decida
como responder. Asi, con UNA sola instancia corriendo, se elige el escenario al publicar:

    EVT-DEMO-1        -> responde 200. Entrega exitosa.
    EVT-RECUPERA-1    -> responde 503 una vez y luego 200. Entrega recuperada por el backoff.
    EVT-FALLA-1       -> responde 503 siempre. Se agotan los reintentos y queda fallida.
    EVT-RECHAZA-1     -> responde 400. Fallo permanente: NO se reintenta.
    EVT-LENTO-1       -> no contesta a tiempo. Timeout del lado del cliente.

El receptor no es parte del sistema: hace de cliente. Es el sistema del cliente el que
decide si acepta o rechaza, y esto lo simula.

Uso:
    python3 scripts/webhook-receiver.py                 # 200, salvo los patrones de arriba
    python3 scripts/webhook-receiver.py --status 500    # falla siempre: agota los reintentos
    python3 scripts/webhook-receiver.py --fail-first 2  # falla los 2 primeros de CUALQUIER evento

Las URLs sembradas apuntan a http://localhost:9090/webhooks/<CLIENT_ID>.
"""

import argparse
import hashlib
import hmac
import json
import time
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, HTTPServer

SECRETS = {
    "CLIENT001": "whsec_client001_local_dev_secret",
    "CLIENT002": "whsec_client002_local_dev_secret",
    "CLIENT003": "whsec_client003_local_dev_secret",
}

attempts_by_event = defaultdict(int)
options = argparse.Namespace(status=200, fail_first=0, fail_pattern="FALLA", fail_times=3,
                             recover_pattern="RECUPERA", recover_after=1,
                             reject_pattern="RECHAZA", slow_pattern="LENTO", slow_seconds=8)


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
        # El cliente se deduce de la ruta, que es como estan sembradas las
        # suscripciones. Cuando se usa WEBHOOK_OVERRIDE_URL todas apuntan a una misma
        # ruta, asi que se cae al client_id del propio cuerpo para poder verificar la
        # firma igual.
        client_id = self.path.rsplit("/", 1)[-1]
        if client_id not in SECRETS:
            try:
                client_id = json.loads(body).get("client_id", client_id)
            except ValueError:
                pass

        attempts_by_event[event_id] += 1
        # Tardar mas que el response-timeout del servicio produce un timeout real: el
        # intento queda "sin respuesta", que es distinto de recibir un codigo de error.
        if options.slow_pattern and options.slow_pattern.upper() in event_id.upper():
            print(f"[{event_id}] no contesto a proposito ({options.slow_seconds}s)", flush=True)
            time.sleep(options.slow_seconds)

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
    id_mayus = event_id.upper()
    # Se evalua primero el patron de recuperacion: falla poco y luego acepta, que es lo
    # que produce una entrega recuperada gracias al backoff.
    if options.recover_pattern and options.recover_pattern.upper() in id_mayus:
        return 503 if intento <= options.recover_after else 200
    if options.fail_pattern and options.fail_pattern.upper() in id_mayus:
        return 503 if intento <= options.fail_times else 200
    # 400 es un contrato roto: el servicio NO lo reintenta, porque insistir con el mismo
    # cuerpo va a dar el mismo 400.
    if options.reject_pattern and options.reject_pattern.upper() in id_mayus:
        return 400
    return options.status


def main() -> None:
    parser = argparse.ArgumentParser(description="Receptor de webhooks de prueba")
    parser.add_argument("--port", type=int, default=9090)
    parser.add_argument("--status", type=int, default=200, help="Codigo a devolver siempre")
    parser.add_argument("--fail-first", type=int, default=0,
                        help="Responde 503 los N primeros intentos de CUALQUIER evento")
    parser.add_argument("--fail-pattern", default="FALLA",
                        help="Los eventos cuyo id contenga este texto fallan sus primeros intentos")
    parser.add_argument("--recover-pattern", default="RECUPERA",
                        help="Los eventos con este texto en el id fallan poco y luego aceptan: "
                             "producen una entrega recuperada por reintentos")
    parser.add_argument("--recover-after", type=int, default=1,
                        help="Cuantos intentos fallan los eventos que se recuperan")
    parser.add_argument("--fail-times", type=int, default=3,
                        help="Cuantos intentos fallan los eventos que coinciden con el patron. Por defecto 3, que es lo que agota el ciclo en el perfil local: asi el evento queda fallido y el reenvio manual -que abre un ciclo nuevo- si se entrega.")
    parser.parse_args(namespace=options)

    print(f"Receptor de webhooks escuchando en http://localhost:{options.port}/webhooks/<CLIENT_ID>",
          flush=True)
    if options.fail_pattern:
        print(f"  '{options.fail_pattern}' en el id -> falla {options.fail_times} intentos "
              f"(agota el ciclo)", flush=True)
    if options.recover_pattern:
        print(f"  '{options.recover_pattern}' en el id -> falla {options.recover_after} y luego "
              f"acepta (se recupera con reintentos)", flush=True)
    print("  cualquier otro id -> se acepta a la primera", flush=True)
    HTTPServer(("0.0.0.0", options.port), WebhookHandler).serve_forever()


if __name__ == "__main__":
    main()
