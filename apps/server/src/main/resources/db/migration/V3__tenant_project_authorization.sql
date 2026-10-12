-- R03-M03-002 B: the first tenant/domain/project authorization slice.
-- All project relationships carry tenant_id so a caller cannot join objects
-- across tenant boundaries by id alone.

CREATE TABLE tenant (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT tenant_code_unique UNIQUE (code),
    CONSTRAINT tenant_code_nonempty CHECK (length(btrim(code)) > 0),
    CONSTRAINT tenant_name_nonempty CHECK (length(btrim(name)) > 0),
    CONSTRAINT tenant_status_valid CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT tenant_row_version_nonnegative CHECK (row_version >= 0)
);

CREATE TABLE domain (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    name TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT domain_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT domain_name_unique UNIQUE (tenant_id, name),
    CONSTRAINT domain_name_nonempty CHECK (length(btrim(name)) > 0),
    CONSTRAINT domain_status_valid CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT domain_row_version_nonnegative CHECK (row_version >= 0)
);

CREATE TABLE tenant_member (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    principal_id UUID NOT NULL REFERENCES principal(id),
    roles TEXT[] NOT NULL DEFAULT ARRAY['TENANT_ADMIN']::TEXT[],
    revoked_at TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 1,
    authorization_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, principal_id),
    CONSTRAINT tenant_member_roles_nonempty CHECK (cardinality(roles) > 0),
    CONSTRAINT tenant_member_roles_valid CHECK (roles <@ ARRAY['TENANT_ADMIN','MEMBER']::TEXT[]),
    CONSTRAINT tenant_member_row_version_nonnegative CHECK (row_version >= 0),
    CONSTRAINT tenant_member_version_positive CHECK (authorization_version > 0)
);

CREATE TABLE project (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    domain_id UUID NOT NULL,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'ACTIVE',
    schema_version INTEGER NOT NULL DEFAULT 1,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_by UUID REFERENCES principal(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT project_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT project_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT project_domain_same_tenant_fk FOREIGN KEY (tenant_id, domain_id)
        REFERENCES domain (tenant_id, id),
    CONSTRAINT project_code_nonempty CHECK (length(btrim(code)) > 0),
    CONSTRAINT project_name_nonempty CHECK (length(btrim(name)) > 0),
    CONSTRAINT project_state_valid CHECK (state IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT project_schema_version_positive CHECK (schema_version > 0),
    CONSTRAINT project_row_version_nonnegative CHECK (row_version >= 0)
);

CREATE TABLE project_member (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    principal_id UUID NOT NULL REFERENCES principal(id),
    roles TEXT[] NOT NULL DEFAULT ARRAY['PROJECT_MEMBER']::TEXT[],
    revoked_at TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 1,
    authorization_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, project_id, principal_id),
    CONSTRAINT project_member_project_fk FOREIGN KEY (tenant_id, project_id)
        REFERENCES project (tenant_id, id),
    CONSTRAINT project_member_tenant_member_fk FOREIGN KEY (tenant_id, principal_id)
        REFERENCES tenant_member (tenant_id, principal_id),
    CONSTRAINT project_member_roles_nonempty CHECK (cardinality(roles) > 0),
    CONSTRAINT project_member_roles_valid CHECK (roles <@ ARRAY['PROJECT_ADMIN','PROJECT_MEMBER','PROJECT_VIEWER']::TEXT[]),
    CONSTRAINT project_member_row_version_nonnegative CHECK (row_version >= 0),
    CONSTRAINT project_member_version_positive CHECK (authorization_version > 0)
);

CREATE INDEX tenant_member_principal_idx ON tenant_member (principal_id, tenant_id);
CREATE INDEX project_member_principal_idx ON project_member (principal_id, tenant_id, project_id);

CREATE TABLE audit_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    project_id UUID,
    actor_principal_id UUID REFERENCES principal(id),
    action TEXT NOT NULL,
    object_type TEXT NOT NULL,
    object_id UUID,
    object_revision BIGINT,
    before_hash TEXT,
    after_hash TEXT,
    provenance TEXT NOT NULL DEFAULT 'application',
    at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT audit_event_project_fk FOREIGN KEY (tenant_id, project_id)
        REFERENCES project (tenant_id, id),
    CONSTRAINT audit_event_action_nonempty CHECK (length(btrim(action)) > 0),
    CONSTRAINT audit_event_object_type_nonempty CHECK (length(btrim(object_type)) > 0),
    CONSTRAINT audit_event_revision_nonnegative CHECK (object_revision IS NULL OR object_revision >= 0)
);

CREATE INDEX audit_event_scope_idx ON audit_event (tenant_id, project_id, at, id);

-- Defense in depth for every pooled runtime connection. Services set these
-- transaction-local values only after resolving the verified principal and
-- tenant; a missing value therefore yields no rows.
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE domain ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE project ENABLE ROW LEVEL SECURITY;
ALTER TABLE project_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_scope ON tenant USING (
    current_setting('test365alm.tenant_id', true) = id::text
    OR EXISTS (SELECT 1 FROM tenant_member tm WHERE tm.tenant_id = tenant.id
        AND tm.principal_id::text = current_setting('test365alm.principal_id', true)
        AND tm.revoked_at IS NULL AND (tm.valid_until IS NULL OR tm.valid_until > CURRENT_TIMESTAMP))
 ) WITH CHECK (
    current_setting('test365alm.tenant_id', true) = id::text
 );
CREATE POLICY domain_scope ON domain USING (
    current_setting('test365alm.tenant_id', true) = tenant_id::text
 ) WITH CHECK (current_setting('test365alm.tenant_id', true) = tenant_id::text);
CREATE POLICY tenant_member_scope ON tenant_member USING (
    current_setting('test365alm.tenant_id', true) = tenant_id::text
    OR current_setting('test365alm.principal_id', true) = principal_id::text
 ) WITH CHECK (current_setting('test365alm.tenant_id', true) = tenant_id::text);
CREATE POLICY project_scope ON project USING (
    current_setting('test365alm.tenant_id', true) = tenant_id::text
    OR EXISTS (SELECT 1 FROM project_member pm WHERE pm.tenant_id = project.tenant_id
        AND pm.project_id = project.id
        AND pm.principal_id::text = current_setting('test365alm.principal_id', true)
        AND pm.revoked_at IS NULL AND (pm.valid_until IS NULL OR pm.valid_until > CURRENT_TIMESTAMP))
    OR EXISTS (SELECT 1 FROM tenant_member tm WHERE tm.tenant_id = project.tenant_id
        AND tm.principal_id::text = current_setting('test365alm.principal_id', true)
        AND tm.revoked_at IS NULL AND (tm.valid_until IS NULL OR tm.valid_until > CURRENT_TIMESTAMP)
        AND tm.roles && ARRAY['TENANT_ADMIN']::text[])
 ) WITH CHECK (current_setting('test365alm.tenant_id', true) = tenant_id::text);
CREATE POLICY project_member_scope ON project_member USING (
    current_setting('test365alm.tenant_id', true) = tenant_id::text
    OR current_setting('test365alm.principal_id', true) = principal_id::text
 ) WITH CHECK (current_setting('test365alm.tenant_id', true) = tenant_id::text);
CREATE POLICY audit_event_scope ON audit_event USING (
    current_setting('test365alm.tenant_id', true) = tenant_id::text
 ) WITH CHECK (current_setting('test365alm.tenant_id', true) = tenant_id::text);

-- The application account is intentionally DML-only. Flyway remains the sole
-- owner of schema changes; deployments without this role simply use explicit
-- grants managed by the environment.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        GRANT SELECT, INSERT, UPDATE ON tenant, domain, tenant_member,
            project, project_member TO test365alm_runtime;
        GRANT SELECT, INSERT ON audit_event TO test365alm_runtime;
        GRANT USAGE ON SCHEMA public TO test365alm_runtime;
    END IF;
END $$;
