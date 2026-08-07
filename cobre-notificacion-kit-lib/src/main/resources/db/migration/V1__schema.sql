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
