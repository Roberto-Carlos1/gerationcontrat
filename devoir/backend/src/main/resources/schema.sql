-- Schéma PostgreSQL 16+. Les données métier sont typées et relationnelles.
CREATE TABLE IF NOT EXISTS registry_metadata (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    initialized BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO registry_metadata (id) VALUES (1) ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS contracts (
    id VARCHAR(200) PRIMARY KEY,
    position INTEGER NOT NULL UNIQUE,
    name VARCHAR(150) NOT NULL CHECK (btrim(name) <> ''),
    supplier VARCHAR(120) NOT NULL CHECK (btrim(supplier) <> ''),
    category VARCHAR(200) NOT NULL,
    amount NUMERIC(15,2) NOT NULL CHECK (amount BETWEEN 0 AND 1000000000000),
    frequency VARCHAR(20) NOT NULL CHECK (frequency IN ('Mensuel','Trimestriel','Annuel')),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL CHECK (end_date >= start_date),
    notice INTEGER NOT NULL CHECK (notice BETWEEN 0 AND 730),
    renew VARCHAR(3) NOT NULL CHECK (renew IN ('Oui','Non')),
    owner VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('Actif','Brouillon','Terminé','Résilié')),
    penalty TEXT NOT NULL,
    notes TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS contracts_end_date_idx ON contracts(end_date);

CREATE TABLE IF NOT EXISTS disputes (
    id VARCHAR(200) PRIMARY KEY,
    position INTEGER NOT NULL UNIQUE,
    contract_id VARCHAR(200) NOT NULL REFERENCES contracts(id),
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    amount NUMERIC(15,2) NOT NULL CHECK (amount BETWEEN 0 AND 1000000000000),
    owner VARCHAR(200) NOT NULL,
    stage INTEGER NOT NULL CHECK (stage BETWEEN 0 AND 3)
);
CREATE INDEX IF NOT EXISTS disputes_contract_idx ON disputes(contract_id);
CREATE TABLE IF NOT EXISTS dispute_history (
    dispute_id VARCHAR(200) NOT NULL REFERENCES disputes(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    description TEXT NOT NULL,
    PRIMARY KEY (dispute_id, position)
);
CREATE TABLE IF NOT EXISTS analyses (
    id INTEGER PRIMARY KEY,
    contract_name VARCHAR(200) NOT NULL,
    created_label VARCHAR(200) NOT NULL,
    validated BOOLEAN NOT NULL
);
CREATE TABLE IF NOT EXISTS analysis_groups (
    analysis_id INTEGER NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    label VARCHAR(200) NOT NULL,
    PRIMARY KEY (analysis_id, position)
);
CREATE TABLE IF NOT EXISTS analysis_excerpts (
    analysis_id INTEGER NOT NULL,
    group_position INTEGER NOT NULL,
    position INTEGER NOT NULL,
    excerpt TEXT NOT NULL,
    PRIMARY KEY (analysis_id, group_position, position),
    FOREIGN KEY (analysis_id, group_position) REFERENCES analysis_groups(analysis_id,position) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS acknowledgements (
    value VARCHAR(240) PRIMARY KEY,
    contract_id VARCHAR(200) NOT NULL REFERENCES contracts(id)
);

-- Migration V2 : ajout non destructif des résultats détaillés de l’IA.
ALTER TABLE analyses ADD COLUMN IF NOT EXISTS ai_details JSONB;

-- Migration V3 : fichiers PDF, texte extrait et association au contrat.
CREATE TABLE IF NOT EXISTS documents (
    id UUID PRIMARY KEY,
    contract_id VARCHAR(200) NOT NULL REFERENCES contracts(id) ON DELETE CASCADE,
    filename VARCHAR(200) NOT NULL,
    byte_size INTEGER NOT NULL CHECK (byte_size BETWEEN 1 AND 10000000),
    pages INTEGER NOT NULL CHECK (pages BETWEEN 1 AND 100),
    content BYTEA NOT NULL,
    extracted_text TEXT NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS documents_contract_idx ON documents(contract_id);

CREATE TABLE IF NOT EXISTS reminder_deliveries (
 delivery_key VARCHAR(240) PRIMARY KEY, contract_id VARCHAR(200) NOT NULL REFERENCES contracts(id) ON DELETE CASCADE,
 reference_date DATE NOT NULL, milestone VARCHAR(2) NOT NULL CHECK(milestone IN ('10','30')),
 claimed_at TIMESTAMPTZ NOT NULL, sent_at TIMESTAMPTZ
);

-- Migration V4 : extension additive du formulaire ; aucune ligne existante modifiée.
ALTER TABLE contracts ALTER COLUMN amount DROP NOT NULL;
ALTER TABLE contracts ALTER COLUMN end_date DROP NOT NULL;
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS details JSONB;
CREATE UNIQUE INDEX IF NOT EXISTS contracts_reference_idx
    ON contracts (lower(btrim(details->>'reference'))) WHERE details IS NOT NULL;
