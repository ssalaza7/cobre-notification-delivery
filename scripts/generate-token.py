#!/usr/bin/env python3
"""Genera un JWT HS256 para probar la API self-service.

Solo para desarrollo y demostracion: en produccion los tokens los emite el IdP de
Cobre y este servicio los valida contra su JWKS.

Uso:
    python3 scripts/generate-token.py CLIENT001
    python3 scripts/generate-token.py CLIENT002 --scopes "notifications:read"
"""

import argparse
import base64
import hashlib
import hmac
import json
import time

DEFAULT_SECRET = "cobre-local-development-secret-key-please-change-me"
DEFAULT_SCOPES = "notifications:read notifications:replay notifications:monitor"


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def generate(client_id: str, scopes: str, secret: str, ttl_seconds: int) -> str:
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "sub": f"api-client-{client_id}",
        "client_id": client_id,
        "scope": scopes,
        "iat": now,
        "exp": now + ttl_seconds,
    }

    signing_input = ".".join([
        b64url(json.dumps(header, separators=(",", ":")).encode()),
        b64url(json.dumps(payload, separators=(",", ":")).encode()),
    ])
    signature = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return f"{signing_input}.{b64url(signature)}"


def main() -> None:
    parser = argparse.ArgumentParser(description="Genera un JWT HS256 de prueba")
    parser.add_argument("client_id", help="Tenant que va a identificar el token, por ejemplo CLIENT001")
    parser.add_argument("--scopes", default=DEFAULT_SCOPES, help="Scopes separados por espacio")
    parser.add_argument("--secret", default=DEFAULT_SECRET, help="Clave HS256 configurada en el servicio")
    parser.add_argument("--ttl", type=int, default=86400, help="Vigencia en segundos (por defecto 24h)")
    args = parser.parse_args()

    print(generate(args.client_id, args.scopes, args.secret, args.ttl))


if __name__ == "__main__":
    main()
