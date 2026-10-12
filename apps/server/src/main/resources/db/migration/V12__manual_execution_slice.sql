-- R06-M09-001: execution-owned test set, manifest, run and attempt data.
-- V1-V11 are immutable; execution never writes back to test_case history.

CREATE TABLE test_set (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    row_version BIGINT NOT NULL DEFAULT 1,
    created_by UUID NOT NULL REFERENCES principal(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT test_set_scope_unique UNIQUE (tenant_id, project_id, id),
    CONSTRAINT test_set_project_fk FOREIGN KEY (tenant_id, project_id) REFERENCES project(tenant_id, id),
    CONSTRAINT test_set_name_nonempty CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    CONSTRAINT test_set_description_size CHECK (length(description) <= 100000)
);

CREATE TABLE test_instance (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    test_set_id UUID NOT NULL,
    test_case_id UUID NOT NULL,
    test_revision_id UUID NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 1,
    created_by UUID NOT NULL REFERENCES principal(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT test_instance_scope_unique UNIQUE (tenant_id, project_id, id),
    CONSTRAINT test_instance_set_fk FOREIGN KEY (tenant_id, project_id, test_set_id) REFERENCES test_set(tenant_id, project_id, id),
    CONSTRAINT test_instance_revision_fk FOREIGN KEY (tenant_id, project_id, test_case_id, test_revision_id)
        REFERENCES test_revision(tenant_id, project_id, test_case_id, id),
    CONSTRAINT test_instance_order_positive CHECK (display_order > 0),
    CONSTRAINT test_instance_revision_unique UNIQUE (tenant_id, project_id, test_set_id, test_case_id, test_revision_id)
);

CREATE TABLE execution_manifest (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    test_instance_id UUID NOT NULL,
    source_test_case_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    source_revision_no BIGINT NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    preconditions TEXT NOT NULL DEFAULT '',
    format_version TEXT NOT NULL,
    rules_version TEXT NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT execution_manifest_scope_unique UNIQUE (tenant_id, project_id, id),
    CONSTRAINT execution_manifest_instance_fk FOREIGN KEY (tenant_id, project_id, test_instance_id)
        REFERENCES test_instance(tenant_id, project_id, id),
    CONSTRAINT execution_manifest_revision_fk FOREIGN KEY (tenant_id, project_id, source_test_case_id, source_revision_id)
        REFERENCES test_revision(tenant_id, project_id, test_case_id, id),
    CONSTRAINT execution_manifest_hash CHECK (snapshot_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE execution_manifest_step (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    manifest_id UUID NOT NULL,
    step_key UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    action TEXT NOT NULL,
    expected TEXT NOT NULL,
    CONSTRAINT execution_manifest_step_pk PRIMARY KEY (manifest_id, step_key),
    CONSTRAINT execution_manifest_step_manifest_fk FOREIGN KEY (tenant_id, project_id, manifest_id)
        REFERENCES execution_manifest(tenant_id, project_id, id),
    CONSTRAINT execution_manifest_step_order_unique UNIQUE (manifest_id, ordinal),
    CONSTRAINT execution_manifest_step_order_positive CHECK (ordinal > 0),
    CONSTRAINT execution_manifest_step_action_nonempty CHECK (length(btrim(action)) BETWEEN 1 AND 10000),
    CONSTRAINT execution_manifest_step_expected_size CHECK (length(expected) <= 10000)
);

CREATE TABLE execution_run (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    test_instance_id UUID NOT NULL,
    manifest_id UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'RUNNING',
    row_version BIGINT NOT NULL DEFAULT 1,
    created_by UUID NOT NULL REFERENCES principal(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT execution_run_scope_unique UNIQUE (tenant_id, project_id, id),
    CONSTRAINT execution_run_instance_fk FOREIGN KEY (tenant_id, project_id, test_instance_id) REFERENCES test_instance(tenant_id, project_id, id),
    CONSTRAINT execution_run_manifest_fk FOREIGN KEY (tenant_id, project_id, manifest_id) REFERENCES execution_manifest(tenant_id, project_id, id),
    CONSTRAINT execution_run_status CHECK (status IN ('RUNNING','PAUSED','FINISHED'))
);

CREATE TABLE run_attempt (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL,
    attempt_no INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'RUNNING',
    conclusion TEXT,
    row_version BIGINT NOT NULL DEFAULT 1,
    started_by UUID NOT NULL REFERENCES principal(id),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT run_attempt_scope_unique UNIQUE (tenant_id, project_id, id),
    CONSTRAINT run_attempt_run_fk FOREIGN KEY (tenant_id, project_id, run_id) REFERENCES execution_run(tenant_id, project_id, id),
    CONSTRAINT run_attempt_no_unique UNIQUE (run_id, attempt_no),
    CONSTRAINT run_attempt_status CHECK (status IN ('RUNNING','PAUSED','FINISHED')),
    CONSTRAINT run_attempt_conclusion CHECK (conclusion IS NULL OR conclusion IN ('PASS','FAIL','BLOCKED'))
);

-- The service checks this condition before a rerun, while this index closes
-- the race between two concurrent rerun transactions at the database edge.
CREATE UNIQUE INDEX run_attempt_one_active_per_run
    ON run_attempt (tenant_id, project_id, run_id)
    WHERE status <> 'FINISHED';

CREATE TABLE run_step (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    step_key UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    action TEXT NOT NULL,
    expected TEXT NOT NULL,
    actual_result TEXT NOT NULL DEFAULT '',
    conclusion TEXT NOT NULL DEFAULT 'NOT_RUN',
    row_version BIGINT NOT NULL DEFAULT 1,
    updated_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT run_step_pk PRIMARY KEY (attempt_id, step_key),
    CONSTRAINT run_step_attempt_fk FOREIGN KEY (tenant_id, project_id, attempt_id) REFERENCES run_attempt(tenant_id, project_id, id),
    CONSTRAINT run_step_order_unique UNIQUE (attempt_id, ordinal),
    CONSTRAINT run_step_conclusion CHECK (conclusion IN ('NOT_RUN','PASS','FAIL','BLOCKED')),
    CONSTRAINT run_step_actual_size CHECK (length(actual_result) <= 100000)
);

CREATE TABLE execution_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    run_id UUID NOT NULL,
    attempt_id UUID,
    event_type TEXT NOT NULL,
    actor_principal_id UUID NOT NULL REFERENCES principal(id),
    event_version BIGINT NOT NULL DEFAULT 1,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT execution_event_run_fk FOREIGN KEY (tenant_id, project_id, run_id) REFERENCES execution_run(tenant_id, project_id, id),
    CONSTRAINT execution_event_attempt_fk FOREIGN KEY (tenant_id, project_id, attempt_id) REFERENCES run_attempt(tenant_id, project_id, id)
);

CREATE TABLE execution_outbox_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    run_id UUID NOT NULL,
    attempt_id UUID,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    CONSTRAINT execution_outbox_run_fk FOREIGN KEY (tenant_id, project_id, run_id) REFERENCES execution_run(tenant_id, project_id, id),
    CONSTRAINT execution_outbox_attempt_fk FOREIGN KEY (tenant_id, project_id, attempt_id) REFERENCES run_attempt(tenant_id, project_id, id)
);

CREATE TABLE execution_idempotency (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    principal_id UUID NOT NULL,
    route TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    object_id UUID NOT NULL,
    object_type TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    PRIMARY KEY (tenant_id, project_id, principal_id, route, idempotency_key),
    CONSTRAINT execution_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT execution_idempotency_key_size CHECK (length(idempotency_key) BETWEEN 8 AND 128)
);

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        GRANT SELECT, INSERT ON test_set, test_instance, execution_manifest, execution_manifest_step,
            execution_run, run_attempt, run_step, execution_event, execution_outbox_event, execution_idempotency TO test365alm_runtime;
        GRANT UPDATE (row_version, updated_at) ON test_set, execution_run, run_step TO test365alm_runtime;
        GRANT UPDATE (status, row_version, updated_at) ON execution_run TO test365alm_runtime;
        GRANT UPDATE (status, conclusion, row_version, finished_at) ON run_attempt TO test365alm_runtime;
        GRANT UPDATE (actual_result, conclusion, row_version, updated_by, updated_at) ON run_step TO test365alm_runtime;
    END IF;
END $$;

ALTER TABLE test_set ENABLE ROW LEVEL SECURITY;
ALTER TABLE test_instance ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution_manifest ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution_manifest_step ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_step ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution_outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution_idempotency ENABLE ROW LEVEL SECURITY;

CREATE POLICY test_set_scope ON test_set USING (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
) WITH CHECK (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_writer(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
);

CREATE POLICY test_instance_scope ON test_instance USING (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
) WITH CHECK (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_writer(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
);

CREATE POLICY execution_manifest_scope ON execution_manifest USING (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
) WITH CHECK (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_writer(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
);
CREATE POLICY execution_manifest_step_scope ON execution_manifest_step USING (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
) WITH CHECK (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_writer(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
);

CREATE POLICY execution_run_scope ON execution_run USING (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
) WITH CHECK (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_writer(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
);
CREATE POLICY run_attempt_scope ON run_attempt USING (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
) WITH CHECK (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_writer(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
);
CREATE POLICY run_step_scope ON run_step USING (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
) WITH CHECK (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_writer(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
);
CREATE POLICY execution_event_scope ON execution_event USING (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
) WITH CHECK (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND actor_principal_id = NULLIF(current_setting('test365alm.principal_id', true), '')::uuid
    AND app_has_active_project_writer(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
);

-- Keep execution audit rows on the existing audit table without reopening the
-- broader legacy policy.  A member may only name a real run in this project
-- and must be the principal in the transaction-local context.
CREATE POLICY execution_audit_insert ON audit_event FOR INSERT WITH CHECK (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND object_type = 'run'
    AND action LIKE 'run.%'
    AND actor_principal_id = NULLIF(current_setting('test365alm.principal_id', true), '')::uuid
    AND EXISTS (SELECT 1 FROM execution_run r WHERE r.tenant_id = audit_event.tenant_id
        AND r.project_id = audit_event.project_id AND r.id = audit_event.object_id)
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
);
CREATE POLICY execution_idempotency_scope ON execution_idempotency USING (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND principal_id = NULLIF(current_setting('test365alm.principal_id', true), '')::uuid
    AND app_has_active_project_writer(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
) WITH CHECK (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND principal_id = NULLIF(current_setting('test365alm.principal_id', true), '')::uuid
    AND app_has_active_project_writer(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
);

CREATE POLICY execution_outbox_scope ON execution_outbox_event USING (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
) WITH CHECK (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '') AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_writer(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
);
