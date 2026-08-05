-- Suscripciones: definen SI un evento debe entregarse y A DONDE.
-- La URL destino se resuelve siempre a partir del client_id del evento; nunca
-- de un parametro de entrada. Esto garantiza que un cliente no pueda recibir
-- notificaciones de eventos que no le pertenecen.
CREATE TABLE subscription (
    id             UUID PRIMARY KEY,
    client_id      VARCHAR(64)   NOT NULL,
    event_type     VARCHAR(64)   NOT NULL, -- '*' = suscrito a todos los tipos
    webhook_url    VARCHAR(2048) NOT NULL,
    signing_secret VARCHAR(255)  NOT NULL, -- clave HMAC para firmar el payload saliente
    active         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX ux_subscription_client_event
    ON subscription (client_id, event_type)
    WHERE active;

-- Estado final de cada notificacion. event_id es la clave natural que llega
-- desde la plataforma: usarla como PK hace la ingesta idempotente sin logica extra.
CREATE TABLE notification_event (
    event_id         VARCHAR(64) PRIMARY KEY,
    client_id        VARCHAR(64) NOT NULL,
    event_type       VARCHAR(64) NOT NULL,
    content          TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL, -- cuando la plataforma genero el evento
    delivery_status  VARCHAR(16) NOT NULL, -- PENDING | RETRYING | COMPLETED | FAILED | DISCARDED
    delivery_date    TIMESTAMPTZ,          -- cuando se cerro la entrega (exito o fallo definitivo)
    attempts         INT         NOT NULL DEFAULT 0,
    replay_count     INT         NOT NULL DEFAULT 0,
    webhook_url      VARCHAR(2048),
    last_http_status INT,
    last_error       VARCHAR(512),
    updated_at       TIMESTAMPTZ NOT NULL
);

-- Indices alineados con los filtros de la API self-service. El client_id va
-- primero en ambos porque toda consulta esta acotada al tenant del token.
CREATE INDEX ix_event_client_created
    ON notification_event (client_id, created_at DESC);

CREATE INDEX ix_event_client_status_created
    ON notification_event (client_id, delivery_status, created_at DESC);

-- Bitacora append-only de cada intento. Alimenta el detalle de la API y las
-- metricas de observabilidad; nunca se actualiza ni se borra.
CREATE TABLE delivery_attempt (
    id             UUID PRIMARY KEY,
    event_id       VARCHAR(64) NOT NULL REFERENCES notification_event (event_id) ON DELETE CASCADE,
    attempt_number INT         NOT NULL,
    replay_count   INT         NOT NULL DEFAULT 0,
    attempted_at   TIMESTAMPTZ NOT NULL,
    outcome        VARCHAR(24) NOT NULL, -- DELIVERED | RETRYABLE_FAILURE | PERMANENT_FAILURE
    http_status    INT,
    duration_ms    BIGINT      NOT NULL,
    error_message  VARCHAR(512)
);

CREATE INDEX ix_attempt_event
    ON delivery_attempt (event_id, attempted_at DESC);
