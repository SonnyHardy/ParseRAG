CREATE TABLE api_keys (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  key_hash VARCHAR(64) NOT NULL UNIQUE,
  owner_email VARCHAR(255) NOT NULL UNIQUE,
  plan VARCHAR(20) DEFAULT 'FREE',
  active BOOLEAN DEFAULT true,
  created_at TIMESTAMP DEFAULT NOW()
);


-- Clé de test : "test-key-dev-123" (hash SHA-256)
INSERT INTO api_keys (key_hash, owner_email, plan)
VALUES ('a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3', 'dev@test.com', 'FREE');
