-- Carga inicial a partir del archivo notification_events.json entregado con la prueba.
--
-- Supuesto documentado: el JSON no trae fecha de creacion del evento, solo
-- `delivery_date`. Como la API debe permitir filtrar por "event creation date",
-- se modela created_at como el instante en que la plataforma genero el evento y
-- se siembra 2 segundos antes de la entrega, que es el orden de magnitud real
-- entre generacion y entrega en un flujo asincrono sano.

INSERT INTO subscription (id, client_id, event_type, webhook_url, signing_secret, active) VALUES
    ('11111111-1111-4111-8111-111111111111', 'CLIENT001', '*', 'http://localhost:9090/webhooks/CLIENT001', 'whsec_client001_local_dev_secret', TRUE),
    ('22222222-2222-4222-8222-222222222222', 'CLIENT002', '*', 'http://localhost:9090/webhooks/CLIENT002', 'whsec_client002_local_dev_secret', TRUE),
    ('33333333-3333-4333-8333-333333333333', 'CLIENT003', '*', 'http://localhost:9090/webhooks/CLIENT003', 'whsec_client003_local_dev_secret', TRUE);

INSERT INTO notification_event (
    event_id, client_id, event_type, content, created_at, delivery_status,
    delivery_date, attempts, replay_count, webhook_url, last_http_status, last_error, updated_at
) VALUES
    ('EVT001', 'CLIENT001', 'credit_card_payment',      'Credit card payment received for $150.00',                  TIMESTAMPTZ '2024-03-15T09:30:20Z', 'COMPLETED', TIMESTAMPTZ '2024-03-15T09:30:22Z', 1, 0, 'http://localhost:9090/webhooks/CLIENT001', 200, NULL, TIMESTAMPTZ '2024-03-15T09:30:22Z'),
    ('EVT002', 'CLIENT001', 'debit_card_withdrawal',    'ATM withdrawal of $200.00',                                 TIMESTAMPTZ '2024-03-15T10:15:43Z', 'COMPLETED', TIMESTAMPTZ '2024-03-15T10:15:45Z', 1, 0, 'http://localhost:9090/webhooks/CLIENT001', 200, NULL, TIMESTAMPTZ '2024-03-15T10:15:45Z'),
    ('EVT003', 'CLIENT002', 'credit_transfer',          'Bank transfer received from Account #4567 for $1,500.00',   TIMESTAMPTZ '2024-03-15T11:20:16Z', 'FAILED',    TIMESTAMPTZ '2024-03-15T11:20:18Z', 5, 0, 'http://localhost:9090/webhooks/CLIENT002', 503, 'Webhook respondio 503 tras agotar los reintentos', TIMESTAMPTZ '2024-03-15T11:20:18Z'),
    ('EVT004', 'CLIENT002', 'debit_automatic_payment',  'Monthly utility bill payment of $85.50',                    TIMESTAMPTZ '2024-03-15T12:05:31Z', 'COMPLETED', TIMESTAMPTZ '2024-03-15T12:05:33Z', 1, 0, 'http://localhost:9090/webhooks/CLIENT002', 200, NULL, TIMESTAMPTZ '2024-03-15T12:05:33Z'),
    ('EVT005', 'CLIENT003', 'credit_refund',            'Refund processed for order #789 for $45.99',                TIMESTAMPTZ '2024-03-15T13:45:08Z', 'FAILED',    TIMESTAMPTZ '2024-03-15T13:45:10Z', 5, 0, 'http://localhost:9090/webhooks/CLIENT003', 500, 'Webhook respondio 500 tras agotar los reintentos', TIMESTAMPTZ '2024-03-15T13:45:10Z'),
    ('EVT006', 'CLIENT003', 'debit_transfer',           'Money transfer sent to Account #8901 for $500.00',          TIMESTAMPTZ '2024-03-15T14:30:53Z', 'COMPLETED', TIMESTAMPTZ '2024-03-15T14:30:55Z', 1, 0, 'http://localhost:9090/webhooks/CLIENT003', 200, NULL, TIMESTAMPTZ '2024-03-15T14:30:55Z'),
    ('EVT007', 'CLIENT001', 'credit_deposit',           'Direct deposit received from Employer XYZ for $2,500.00',   TIMESTAMPTZ '2024-03-15T15:20:38Z', 'COMPLETED', TIMESTAMPTZ '2024-03-15T15:20:40Z', 1, 0, 'http://localhost:9090/webhooks/CLIENT001', 200, NULL, TIMESTAMPTZ '2024-03-15T15:20:40Z'),
    ('EVT008', 'CLIENT002', 'debit_purchase',           'Point of sale purchase at Store ABC for $75.25',            TIMESTAMPTZ '2024-03-15T16:10:13Z', 'COMPLETED', TIMESTAMPTZ '2024-03-15T16:10:15Z', 1, 0, 'http://localhost:9090/webhooks/CLIENT002', 200, NULL, TIMESTAMPTZ '2024-03-15T16:10:15Z'),
    ('EVT009', 'CLIENT003', 'credit_cashback',          'Cashback reward credited for $25.00',                       TIMESTAMPTZ '2024-03-15T17:25:28Z', 'FAILED',    TIMESTAMPTZ '2024-03-15T17:25:30Z', 5, 0, 'http://localhost:9090/webhooks/CLIENT003', 504, 'Timeout del webhook tras agotar los reintentos',   TIMESTAMPTZ '2024-03-15T17:25:30Z'),
    ('EVT010', 'CLIENT001', 'debit_subscription',       'Monthly streaming service payment of $14.99',               TIMESTAMPTZ '2024-03-15T18:05:10Z', 'COMPLETED', TIMESTAMPTZ '2024-03-15T18:05:12Z', 1, 0, 'http://localhost:9090/webhooks/CLIENT001', 200, NULL, TIMESTAMPTZ '2024-03-15T18:05:12Z');

-- Historial de intentos coherente con el estado sembrado: un unico intento para
-- los eventos entregados, cinco para los que terminaron en FAILED.
INSERT INTO delivery_attempt (id, event_id, attempt_number, replay_count, attempted_at, outcome, http_status, duration_ms, error_message)
SELECT gen_random_uuid(), e.event_id, 1, 0, e.delivery_date, 'DELIVERED', 200, 120, NULL
FROM notification_event e
WHERE e.delivery_status = 'COMPLETED';

INSERT INTO delivery_attempt (id, event_id, attempt_number, replay_count, attempted_at, outcome, http_status, duration_ms, error_message)
SELECT gen_random_uuid(), e.event_id, n, 0,
       e.delivery_date - make_interval(secs => (5 - n) * 30),
       'RETRYABLE_FAILURE', e.last_http_status, 5000, e.last_error
FROM notification_event e
CROSS JOIN generate_series(1, 5) AS n
WHERE e.delivery_status = 'FAILED';
