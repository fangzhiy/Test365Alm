CREATE TABLE platform_metadata (
    metadata_key TEXT PRIMARY KEY,
    metadata_value TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO platform_metadata (metadata_key, metadata_value)
VALUES ('schema-purpose', 'Test365Alm platform bootstrap metadata')
ON CONFLICT (metadata_key) DO NOTHING;
