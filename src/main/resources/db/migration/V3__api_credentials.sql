-- Credenciales con las que un cliente obtiene un token de acceso.
--
-- pgcrypto se usa solo para sembrar: permite guardar el hash sin tener que incrustar
-- literales bcrypt ilegibles en el archivo, y deja el secreto en claro unicamente en
-- esta migracion de datos de ejemplo.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE api_credential (
    client_id     VARCHAR(64) PRIMARY KEY,
    -- Hash bcrypt. El secreto en claro no existe en ninguna parte del sistema: si
    -- alguien obtiene un volcado de esta tabla, no puede suplantar a ningun cliente.
    secret_hash   VARCHAR(255) NOT NULL,
    scopes        VARCHAR(512) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_used_at  TIMESTAMPTZ
);

-- Credenciales de demostracion. Los tres clientes del archivo notification_events.json.
--
-- CLIENT003 no tiene permiso de reenvio a proposito: sirve para demostrar que consultar
-- y reenviar son autorizaciones distintas, y que un token de solo lectura recibe 403 al
-- intentar disparar un reenvio.
INSERT INTO api_credential (client_id, secret_hash, scopes) VALUES
    ('CLIENT001', crypt('demo-secret-client001', gen_salt('bf', 10)),
     'notifications:read notifications:replay notifications:monitor'),
    ('CLIENT002', crypt('demo-secret-client002', gen_salt('bf', 10)),
     'notifications:read notifications:replay notifications:monitor'),
    ('CLIENT003', crypt('demo-secret-client003', gen_salt('bf', 10)),
     'notifications:read');
