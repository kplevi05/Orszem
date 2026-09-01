-- Őrszem Demo v1 — reset alap
-- A katalógust nem törli; azt Flyway kezeli.
-- KIZÁRÓLAG local/demo adatbázison futtasd.

TRUNCATE TABLE audit_events, reports RESTART IDENTITY;
DELETE FROM users WHERE username = 'demo.service';
