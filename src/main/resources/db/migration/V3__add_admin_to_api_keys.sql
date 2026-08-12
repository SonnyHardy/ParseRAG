-- Flag « clé administrateur » (issue #37).
-- Seule une clé admin peut appeler GET /api/v1/health : l'endpoint n'est pas public.
ALTER TABLE api_keys ADD COLUMN admin BOOLEAN NOT NULL DEFAULT false;
