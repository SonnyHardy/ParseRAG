CREATE TABLE usage_records (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  api_key_id UUID REFERENCES api_keys(id),
  year_month VARCHAR(7) NOT NULL,    -- mois de début du cycle courant, format "2025-05"
  docs_processed INT DEFAULT 0,
  last_updated TIMESTAMP DEFAULT NOW(),
  UNIQUE(api_key_id, year_month)
);
