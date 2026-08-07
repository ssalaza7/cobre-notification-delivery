-- Credenciales de DEMOSTRACION. Solo se cargan en los perfiles `local` y `demo`.
--
-- Existen para que quien evalue el proyecto pueda consumir la API sin un proceso de
-- onboarding. No sirven en ningun entorno real: la migracion no esta en la ruta que
-- carga el perfil por defecto.
--
-- Aun asi, el secreto se guarda hasheado con bcrypt y nunca en claro, igual que en
-- produccion. Lo unico que esta en el repositorio es el valor de demostracion, que no
-- da acceso a nada fuera de una base local desechable.
--
-- CLIENT003 se registra a proposito solo con permiso de lectura, para demostrar que
-- consultar y reenviar son autorizaciones distintas.
INSERT INTO api_credential (client_id, secret_hash, scopes) VALUES
    ('CLIENT001', crypt('demo-secret-client001', gen_salt('bf', 10)),
     'notifications:read notifications:replay notifications:monitor subscriptions:manage'),
    ('CLIENT002', crypt('demo-secret-client002', gen_salt('bf', 10)),
     'notifications:read notifications:replay notifications:monitor subscriptions:manage'),
    ('CLIENT003', crypt('demo-secret-client003', gen_salt('bf', 10)),
     'notifications:read')
ON CONFLICT (client_id) DO NOTHING;
