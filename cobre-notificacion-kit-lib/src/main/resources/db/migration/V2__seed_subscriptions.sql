-- Suscripciones de los clientes de ejemplo.
--
-- Las notificaciones y su bitacora ya no se siembran aqui: viven en DynamoDB y las
-- escribe DynamoDbDemoSeeder, que solo actua en los perfiles `local` y `demo`. Aqui
-- quedan las suscripciones porque siguen siendo relacionales: son configuracion del
-- cliente, fuera del flujo de entrega, con una unicidad por (client_id, event_type)
-- que la base hace cumplir.

INSERT INTO subscription (id, client_id, event_type, webhook_url, signing_secret, active) VALUES
    ('11111111-1111-4111-8111-111111111111', 'CLIENT001', '*', 'http://localhost:9090/webhooks/CLIENT001', 'whsec_client001_local_dev_secret', TRUE),
    ('22222222-2222-4222-8222-222222222222', 'CLIENT002', '*', 'http://localhost:9090/webhooks/CLIENT002', 'whsec_client002_local_dev_secret', TRUE),
    ('33333333-3333-4333-8333-333333333333', 'CLIENT003', '*', 'http://localhost:9090/webhooks/CLIENT003', 'whsec_client003_local_dev_secret', TRUE);
