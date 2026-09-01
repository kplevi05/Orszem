-- Őrszem Demo v1 — demo-only service user
-- KIZÁRÓLAG local/demo környezetben futtatható.
-- Login: demo.service
-- Demo jelszó: OrszemDemo!2026
-- Az adatbázisban csak Argon2id hash kerül tárolásra.

INSERT INTO users (
    id, username, display_name, password_hash, role, status, created_at, updated_at
)
VALUES (
    'e3d40706-d8fd-54f8-9a5d-5361da511b5f'::uuid,
    'demo.service',
    'Demo Szolgálat',
    '$argon2id$v=19$m=65536,t=3,p=1$Zy0MIWaj43hGP4K6heD5uQ$hMSwITfN1YwAALmaijG23wQgOw3w2gLVzS0LjWLxBJo',
    'SERVICE_USER',
    'ACTIVE',
    now(),
    now()
)
ON CONFLICT (username) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    password_hash = EXCLUDED.password_hash,
    role = EXCLUDED.role,
    status = EXCLUDED.status,
    updated_at = now();
