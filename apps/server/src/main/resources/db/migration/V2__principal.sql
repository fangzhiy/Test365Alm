CREATE TABLE principal (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    issuer TEXT NOT NULL,
    subject TEXT NOT NULL,
    display_name TEXT NOT NULL,
    disabled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT principal_issuer_subject_unique UNIQUE (issuer, subject),
    CONSTRAINT principal_issuer_nonempty CHECK (length(issuer) > 0),
    CONSTRAINT principal_subject_nonempty CHECK (length(subject) > 0)
);

-- The local disposable Compose environment creates this role before Flyway.
-- Other environments must provision their own runtime role and explicit grants.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        GRANT USAGE ON SCHEMA public TO test365alm_runtime;
        GRANT SELECT ON platform_metadata, flyway_schema_history TO test365alm_runtime;
        GRANT SELECT, INSERT, UPDATE ON principal TO test365alm_runtime;
    END IF;
END $$;
