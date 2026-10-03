-- R05-M08-001: project-scoped manual test case identities, immutable
-- revisions and step snapshots.  V1-V9 are immutable; this migration adds
-- only the first M08 slice and does not introduce execution/configuration.

CREATE TABLE test_case_number_allocator (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    project_id UUID NOT NULL,
    next_number BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, project_id),
    CONSTRAINT test_case_allocator_project_fk FOREIGN KEY (tenant_id, project_id)
        REFERENCES project (tenant_id, id),
    CONSTRAINT test_case_allocator_positive CHECK (next_number > 0)
);

CREATE TABLE test_case (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    id UUID PRIMARY KEY,
    display_number BIGINT NOT NULL,
    test_type TEXT NOT NULL DEFAULT 'MANUAL',
    current_revision_id UUID,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL REFERENCES principal(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT test_case_scope_unique UNIQUE (tenant_id, project_id, id),
    CONSTRAINT test_case_number_unique UNIQUE (tenant_id, project_id, display_number),
    CONSTRAINT test_case_number_positive CHECK (display_number > 0),
    CONSTRAINT test_case_version_positive CHECK (row_version > 0),
    CONSTRAINT test_case_type_manual CHECK (test_type = 'MANUAL'),
    CONSTRAINT test_case_project_fk FOREIGN KEY (tenant_id, project_id)
        REFERENCES project (tenant_id, id)
);

CREATE TABLE test_revision (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    test_case_id UUID NOT NULL,
    id UUID PRIMARY KEY,
    revision_no BIGINT NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    preconditions TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL REFERENCES principal(id),
    CONSTRAINT test_revision_scope_unique UNIQUE (tenant_id, project_id, id),
    CONSTRAINT test_revision_identity_unique UNIQUE (tenant_id, project_id, test_case_id, id),
    CONSTRAINT test_revision_case_fk FOREIGN KEY (tenant_id, project_id, test_case_id)
        REFERENCES test_case (tenant_id, project_id, id),
    CONSTRAINT test_revision_no_unique UNIQUE (tenant_id, project_id, test_case_id, revision_no),
    CONSTRAINT test_revision_no_positive CHECK (revision_no > 0),
    CONSTRAINT test_title_nonempty CHECK (length(btrim(title)) BETWEEN 1 AND 500),
    CONSTRAINT test_description_size CHECK (length(description) <= 100000),
    CONSTRAINT test_preconditions_size CHECK (length(preconditions) <= 100000)
);

ALTER TABLE test_case
    ADD CONSTRAINT test_case_current_revision_fk
    FOREIGN KEY (tenant_id, project_id, id, current_revision_id)
    REFERENCES test_revision (tenant_id, project_id, test_case_id, id);

CREATE TABLE test_step (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    test_case_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    step_key UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    action TEXT NOT NULL,
    expected TEXT NOT NULL,
    CONSTRAINT test_step_pk PRIMARY KEY (revision_id, step_key),
    CONSTRAINT test_step_revision_fk FOREIGN KEY (tenant_id, project_id, test_case_id, revision_id)
        REFERENCES test_revision (tenant_id, project_id, test_case_id, id),
    CONSTRAINT test_step_ordinal_unique UNIQUE (tenant_id, project_id, test_case_id, revision_id, ordinal),
    CONSTRAINT test_step_ordinal_positive CHECK (ordinal > 0),
    CONSTRAINT test_step_action_nonempty CHECK (length(btrim(action)) BETWEEN 1 AND 10000),
    CONSTRAINT test_step_expected_size CHECK (length(expected) <= 10000)
);

CREATE INDEX test_case_project_number_idx ON test_case (tenant_id, project_id, display_number);
CREATE INDEX test_revision_history_idx ON test_revision (tenant_id, project_id, test_case_id, revision_no DESC);
CREATE INDEX test_step_revision_order_idx ON test_step (tenant_id, project_id, test_case_id, revision_id, ordinal);

CREATE TABLE test_case_outbox_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    test_case_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    payload_version INTEGER NOT NULL DEFAULT 1,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    CONSTRAINT test_case_outbox_case_fk FOREIGN KEY (tenant_id, project_id, test_case_id)
        REFERENCES test_case (tenant_id, project_id, id),
    CONSTRAINT test_case_outbox_revision_fk FOREIGN KEY (tenant_id, project_id, test_case_id, revision_id)
        REFERENCES test_revision (tenant_id, project_id, test_case_id, id),
    CONSTRAINT test_case_outbox_type_nonempty CHECK (length(btrim(event_type)) > 0),
    CONSTRAINT test_case_outbox_version_positive CHECK (payload_version > 0)
);
CREATE INDEX test_case_outbox_unpublished_idx ON test_case_outbox_event (occurred_at)
    WHERE published_at IS NULL;

CREATE TABLE test_case_idempotency (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    principal_id UUID NOT NULL,
    route TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    test_case_id UUID,
    revision_id UUID,
    result_display_number BIGINT,
    result_row_version BIGINT,
    result_revision_no BIGINT,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, project_id, principal_id, route, idempotency_key),
    CONSTRAINT test_case_idempotency_case_fk FOREIGN KEY (tenant_id, project_id, test_case_id)
        REFERENCES test_case (tenant_id, project_id, id),
    CONSTRAINT test_case_idempotency_revision_fk FOREIGN KEY (tenant_id, project_id, test_case_id, revision_id)
        REFERENCES test_revision (tenant_id, project_id, test_case_id, id),
    CONSTRAINT test_case_idempotency_pair CHECK ((test_case_id IS NULL) = (revision_id IS NULL)),
    CONSTRAINT test_case_idempotency_key_size CHECK (length(idempotency_key) BETWEEN 8 AND 128),
    CONSTRAINT test_case_idempotency_hash_size CHECK (length(request_hash) = 64)
);
CREATE INDEX test_case_idempotency_expiry_idx ON test_case_idempotency (expires_at);

ALTER TABLE test_case_number_allocator ENABLE ROW LEVEL SECURITY;
ALTER TABLE test_case ENABLE ROW LEVEL SECURITY;
ALTER TABLE test_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE test_step ENABLE ROW LEVEL SECURITY;
ALTER TABLE test_case_outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE test_case_idempotency ENABLE ROW LEVEL SECURITY;

CREATE POLICY test_case_allocator_scope ON test_case_number_allocator
    USING (tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
       AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
       AND app_has_active_project_writer(tenant_id, project_id,
           NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
    WITH CHECK (tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
       AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
       AND app_has_active_project_writer(tenant_id, project_id,
           NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));

CREATE POLICY test_case_select ON test_case FOR SELECT USING (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));
CREATE POLICY test_case_insert ON test_case FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND created_by = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));
CREATE POLICY test_case_update ON test_case FOR UPDATE USING (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
    WITH CHECK (tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), ''));

CREATE POLICY test_revision_select ON test_revision FOR SELECT USING (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));
CREATE POLICY test_revision_insert ON test_revision FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND created_by = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));

CREATE POLICY test_step_select ON test_step FOR SELECT USING (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));
CREATE POLICY test_step_insert ON test_step FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));

CREATE POLICY test_case_outbox_select ON test_case_outbox_event FOR SELECT USING (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));
CREATE POLICY test_case_outbox_insert ON test_case_outbox_event FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));

CREATE POLICY test_case_idempotency_scope ON test_case_idempotency USING (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND principal_id = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
    WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND principal_id = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));

-- Extend the existing application audit boundary only for the strongly typed
-- test-case actions.  Requirement and project administration rules remain
-- unchanged; viewers cannot append test-case audit rows.
DROP POLICY IF EXISTS audit_event_insert ON audit_event;
CREATE POLICY audit_event_insert ON audit_event FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND app_has_active_tenant_member(tenant_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    AND (
        (project_id IS NULL AND app_has_tenant_admin(tenant_id,
            NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
        OR (project_id IS NOT NULL AND (
            app_has_tenant_admin(tenant_id,
                NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
            OR app_has_project_admin(tenant_id, project_id,
                NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
            OR (action LIKE 'requirement.%' AND app_has_active_project_writer(tenant_id, project_id,
                NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
            OR (action LIKE 'test_case.%' AND app_has_active_project_writer(tenant_id, project_id,
                NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
        ))
    )
);

-- V4 left a broad audit_event_scope policy in place.  PostgreSQL combines
-- permissive policies with OR, so the narrower V8 audit_event_insert policy
-- alone cannot prevent a viewer from inserting arbitrary audit rows.  V10 is
-- the first migration in this branch that can safely remove that legacy
-- policy before installing the test-case audit policy below.
DROP POLICY IF EXISTS audit_event_scope ON audit_event;

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        GRANT SELECT, INSERT ON test_case_number_allocator TO test365alm_runtime;
        GRANT UPDATE (next_number) ON test_case_number_allocator TO test365alm_runtime;
        GRANT SELECT, INSERT ON test_case TO test365alm_runtime;
        GRANT UPDATE (current_revision_id, row_version, updated_at) ON test_case TO test365alm_runtime;
        GRANT SELECT, INSERT ON test_revision, test_step, test_case_outbox_event TO test365alm_runtime;
        GRANT SELECT, INSERT ON test_case_idempotency TO test365alm_runtime;
        GRANT UPDATE (test_case_id, revision_id, result_display_number, result_row_version, result_revision_no)
            ON test_case_idempotency TO test365alm_runtime;
        GRANT DELETE ON test_case_idempotency TO test365alm_runtime;
    END IF;
END $$;

