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

-- Esta migracion NO siembra credenciales. En produccion las filas de esta tabla las
-- crea el proceso de onboarding del cliente: se genera un secreto aleatorio, se le
-- muestra una unica vez y solo se persiste su hash. Sembrar credenciales desde una
-- migracion pondria secretos en el repositorio y en el historial de git.
--
-- Las credenciales de demostracion viven en db/demo, que solo se carga en los perfiles
-- `local` y `demo` (ver spring.flyway.locations).
