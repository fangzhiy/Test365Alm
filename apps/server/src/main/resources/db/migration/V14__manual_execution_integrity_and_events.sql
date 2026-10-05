-- R06-M09-001-FIX01.  Close ownership, snapshot and terminal-state gaps in
-- the first manual execution slice.  This migration is additive; V1-V13 are
-- never edited or re-run.

-- A manifest is assembled inside the create-run transaction and becomes
-- immutable once all snapshot steps have been written.
ALTER TABLE execution_manifest
    ADD COLUMN build_complete BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE execution_manifest SET build_complete = TRUE;

ALTER TABLE test_instance
    ADD CONSTRAINT test_instance_source_scope_unique
    UNIQUE (tenant_id, project_id, id, test_case_id, test_revision_id);
ALTER TABLE execution_manifest
    ADD CONSTRAINT execution_manifest_source_instance_fk
    FOREIGN KEY (tenant_id, project_id, test_instance_id, source_test_case_id, source_revision_id)
    REFERENCES test_instance (tenant_id, project_id, id, test_case_id, test_revision_id);
ALTER TABLE execution_manifest
    ADD CONSTRAINT execution_manifest_id_instance_unique
    UNIQUE (tenant_id, project_id, id, test_instance_id);

-- Keep every attempt and run step tied to the exact manifest selected by the
-- instance. Existing rows are backfilled from their run before constraints
-- are made strict.
ALTER TABLE run_attempt ADD COLUMN manifest_id UUID;
UPDATE run_attempt a
SET manifest_id = r.manifest_id
FROM execution_run r
WHERE r.tenant_id = a.tenant_id AND r.project_id = a.project_id AND r.id = a.run_id;
ALTER TABLE run_attempt ALTER COLUMN manifest_id SET NOT NULL;
ALTER TABLE run_attempt
    ADD CONSTRAINT run_attempt_manifest_fk
    FOREIGN KEY (tenant_id, project_id, manifest_id)
    REFERENCES execution_manifest (tenant_id, project_id, id);
ALTER TABLE run_attempt
    ADD CONSTRAINT run_attempt_run_id_unique
    UNIQUE (tenant_id, project_id, run_id, id);
ALTER TABLE run_attempt
    ADD CONSTRAINT run_attempt_manifest_unique
    UNIQUE (tenant_id, project_id, id, manifest_id);

ALTER TABLE execution_run
    ADD CONSTRAINT execution_run_manifest_instance_fk
    FOREIGN KEY (tenant_id, project_id, manifest_id, test_instance_id)
    REFERENCES execution_manifest (tenant_id, project_id, id, test_instance_id);
ALTER TABLE execution_run
    ADD CONSTRAINT execution_run_manifest_unique
    UNIQUE (tenant_id, project_id, id, manifest_id);
ALTER TABLE run_attempt
    ADD CONSTRAINT run_attempt_run_manifest_fk
    FOREIGN KEY (tenant_id, project_id, run_id, manifest_id)
    REFERENCES execution_run (tenant_id, project_id, id, manifest_id);

ALTER TABLE run_step ADD COLUMN manifest_id UUID;
UPDATE run_step s
SET manifest_id = a.manifest_id
FROM run_attempt a
WHERE a.tenant_id = s.tenant_id AND a.project_id = s.project_id AND a.id = s.attempt_id;
ALTER TABLE run_step ALTER COLUMN manifest_id SET NOT NULL;
ALTER TABLE execution_manifest_step
    ADD CONSTRAINT execution_manifest_step_scope_unique
    UNIQUE (tenant_id, project_id, manifest_id, step_key);
ALTER TABLE run_step
    ADD CONSTRAINT run_step_attempt_manifest_fk
    FOREIGN KEY (tenant_id, project_id, attempt_id, manifest_id)
    REFERENCES run_attempt (tenant_id, project_id, id, manifest_id);
ALTER TABLE run_step
    ADD CONSTRAINT run_step_manifest_step_fk
    FOREIGN KEY (tenant_id, project_id, manifest_id, step_key)
    REFERENCES execution_manifest_step (tenant_id, project_id, manifest_id, step_key);

ALTER TABLE test_set ADD CONSTRAINT test_set_row_version_positive CHECK (row_version > 0);
ALTER TABLE execution_run ADD CONSTRAINT execution_run_row_version_positive CHECK (row_version > 0);
ALTER TABLE run_attempt ADD CONSTRAINT run_attempt_row_version_positive CHECK (row_version > 0);
ALTER TABLE run_step ADD CONSTRAINT run_step_row_version_positive CHECK (row_version > 0);
ALTER TABLE run_attempt ADD CONSTRAINT run_attempt_finished_state_consistent CHECK (
    (status = 'FINISHED') = (finished_at IS NOT NULL)
    AND (status <> 'FINISHED' OR conclusion IS NOT NULL)
);

-- A run event can describe set/instance construction as well as a run.  Keep
-- run_id NULL for those events rather than abusing it as a polymorphic ID.
ALTER TABLE execution_event ALTER COLUMN run_id DROP NOT NULL;
ALTER TABLE execution_outbox_event ALTER COLUMN run_id DROP NOT NULL;
ALTER TABLE execution_event ADD COLUMN object_type TEXT;
ALTER TABLE execution_event ADD COLUMN object_id UUID;
ALTER TABLE execution_outbox_event ADD COLUMN object_type TEXT;
ALTER TABLE execution_outbox_event ADD COLUMN object_id UUID;
ALTER TABLE execution_event ADD CONSTRAINT execution_event_target_shape CHECK (
    (run_id IS NOT NULL AND object_type IS NULL AND object_id IS NULL)
    OR (run_id IS NULL AND object_type IS NOT NULL AND object_id IS NOT NULL)
);
ALTER TABLE execution_outbox_event ADD CONSTRAINT execution_outbox_target_shape CHECK (
    (run_id IS NOT NULL AND object_type IS NULL AND object_id IS NULL)
    OR (run_id IS NULL AND object_type IS NOT NULL AND object_id IS NOT NULL)
);
ALTER TABLE execution_event DROP CONSTRAINT IF EXISTS execution_event_attempt_fk;
ALTER TABLE execution_event ADD CONSTRAINT execution_event_attempt_run_fk
    FOREIGN KEY (tenant_id, project_id, run_id, attempt_id)
    REFERENCES run_attempt (tenant_id, project_id, run_id, id);
ALTER TABLE execution_outbox_event DROP CONSTRAINT IF EXISTS execution_outbox_attempt_fk;
ALTER TABLE execution_outbox_event ADD CONSTRAINT execution_outbox_attempt_run_fk
    FOREIGN KEY (tenant_id, project_id, run_id, attempt_id)
    REFERENCES run_attempt (tenant_id, project_id, run_id, id);

-- Claims retain the first response so a replay does not become a read of the
-- target's later mutable state.
ALTER TABLE execution_idempotency ADD COLUMN response_json JSONB;

CREATE OR REPLACE FUNCTION execution_manifest_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF COALESCE(current_setting('test365alm.execution_building', true), 'false') <> 'true' THEN
            RAISE EXCEPTION 'execution manifest may only be created by the execution builder' USING ERRCODE = '55006';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE'
       AND OLD.build_complete = FALSE
       AND NEW.build_complete = TRUE
       AND COALESCE(current_setting('test365alm.execution_building', true), 'false') = 'true'
       AND NEW.tenant_id = OLD.tenant_id
       AND NEW.project_id = OLD.project_id
       AND NEW.id = OLD.id
       AND NEW.test_instance_id = OLD.test_instance_id
       AND NEW.source_test_case_id = OLD.source_test_case_id
       AND NEW.source_revision_id = OLD.source_revision_id
       AND NEW.source_revision_no = OLD.source_revision_no
       AND NEW.title = OLD.title
       AND NEW.description = OLD.description
       AND NEW.preconditions = OLD.preconditions
       AND NEW.format_version = OLD.format_version
       AND NEW.rules_version = OLD.rules_version
       AND NEW.snapshot_hash = OLD.snapshot_hash
       AND NEW.created_at = OLD.created_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'execution manifest is immutable' USING ERRCODE = '55006';
END
$$;
DROP TRIGGER IF EXISTS execution_manifest_immutable_trigger ON execution_manifest;
CREATE TRIGGER execution_manifest_immutable_trigger
    BEFORE INSERT OR UPDATE OR DELETE ON execution_manifest
    FOR EACH ROW EXECUTE FUNCTION execution_manifest_immutable();

CREATE OR REPLACE FUNCTION execution_manifest_step_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF COALESCE(current_setting('test365alm.execution_building', true), 'false') <> 'true' THEN
            RAISE EXCEPTION 'execution manifest steps may only be created by the execution builder' USING ERRCODE = '55006';
        END IF;
        IF EXISTS (SELECT 1 FROM execution_manifest m
                   WHERE m.tenant_id = NEW.tenant_id AND m.project_id = NEW.project_id
                     AND m.id = NEW.manifest_id AND m.build_complete) THEN
            RAISE EXCEPTION 'execution manifest steps are sealed' USING ERRCODE = '55006';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'execution manifest steps are immutable' USING ERRCODE = '55006';
END
$$;
DROP TRIGGER IF EXISTS execution_manifest_step_immutable_trigger ON execution_manifest_step;
CREATE TRIGGER execution_manifest_step_immutable_trigger
    BEFORE INSERT OR UPDATE OR DELETE ON execution_manifest_step
    FOR EACH ROW EXECUTE FUNCTION execution_manifest_step_immutable();

CREATE OR REPLACE FUNCTION execution_attempt_terminal_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' AND COALESCE(current_setting('test365alm.execution_building', true), 'false') <> 'true' THEN
        RAISE EXCEPTION 'attempts may only be created by the execution builder' USING ERRCODE = '55006';
    END IF;
    IF TG_OP = 'INSERT' THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' OR OLD.status = 'FINISHED' THEN
        RAISE EXCEPTION 'finished attempt is immutable' USING ERRCODE = '55006';
    END IF;
    IF NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.manifest_id IS DISTINCT FROM OLD.manifest_id
       OR NEW.attempt_no IS DISTINCT FROM OLD.attempt_no
       OR NEW.started_by IS DISTINCT FROM OLD.started_by
       OR NEW.started_at IS DISTINCT FROM OLD.started_at
       OR NEW.row_version <> OLD.row_version + 1
       OR (OLD.status = 'RUNNING' AND NEW.status NOT IN ('RUNNING', 'PAUSED', 'FINISHED'))
       OR (OLD.status = 'PAUSED' AND NEW.status NOT IN ('RUNNING', 'FINISHED', 'PAUSED'))
       OR (NEW.status = 'FINISHED' AND (NEW.finished_at IS NULL OR NEW.conclusion IS NULL))
       OR (NEW.status <> 'FINISHED' AND NEW.finished_at IS NOT NULL) THEN
        RAISE EXCEPTION 'invalid attempt state transition' USING ERRCODE = '55006';
    END IF;
    RETURN NEW;
END
$$;
DROP TRIGGER IF EXISTS execution_attempt_terminal_guard_trigger ON run_attempt;
CREATE TRIGGER execution_attempt_terminal_guard_trigger
    BEFORE INSERT OR UPDATE OR DELETE ON run_attempt
    FOR EACH ROW EXECUTE FUNCTION execution_attempt_terminal_guard();

CREATE OR REPLACE FUNCTION execution_step_terminal_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE state TEXT;
BEGIN
    IF TG_OP = 'INSERT' AND COALESCE(current_setting('test365alm.execution_building', true), 'false') <> 'true' THEN
        RAISE EXCEPTION 'run steps may only be created by the execution builder' USING ERRCODE = '55006';
    END IF;
    IF TG_OP = 'INSERT' THEN
        RETURN NEW;
    END IF;
    SELECT status INTO state FROM run_attempt WHERE id = OLD.attempt_id;
    IF TG_OP = 'DELETE' OR state <> 'RUNNING' THEN
        RAISE EXCEPTION 'run step is not editable in the current attempt state' USING ERRCODE = '55006';
    END IF;
    IF NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
       OR NEW.manifest_id IS DISTINCT FROM OLD.manifest_id
       OR NEW.step_key IS DISTINCT FROM OLD.step_key
       OR NEW.ordinal IS DISTINCT FROM OLD.ordinal
       OR NEW.action IS DISTINCT FROM OLD.action
       OR NEW.expected IS DISTINCT FROM OLD.expected
       OR NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION 'run step identity or version is immutable' USING ERRCODE = '55006';
    END IF;
    RETURN NEW;
END
$$;
DROP TRIGGER IF EXISTS execution_step_terminal_guard_trigger ON run_step;
CREATE TRIGGER execution_step_terminal_guard_trigger
    BEFORE INSERT OR UPDATE OR DELETE ON run_step
    FOR EACH ROW EXECUTE FUNCTION execution_step_terminal_guard();

-- Permit scoped run, set and instance audit targets, but never let a runtime
-- member invent an object outside the current project.
DROP POLICY IF EXISTS execution_audit_insert ON audit_event;
CREATE POLICY execution_audit_insert ON audit_event FOR INSERT WITH CHECK (
    tenant_id::text = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::text = NULLIF(current_setting('test365alm.project_id', true), '')
    AND actor_principal_id = NULLIF(current_setting('test365alm.principal_id', true), '')::uuid
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::uuid)
    AND ((object_type = 'run' AND action LIKE 'run.%' AND EXISTS (SELECT 1 FROM execution_run r
          WHERE r.tenant_id = audit_event.tenant_id AND r.project_id = audit_event.project_id AND r.id = audit_event.object_id))
      OR (object_type = 'test_set' AND action = 'test.set.created' AND EXISTS (SELECT 1 FROM test_set s
          WHERE s.tenant_id = audit_event.tenant_id AND s.project_id = audit_event.project_id AND s.id = audit_event.object_id))
      OR (object_type = 'test_instance' AND action = 'test.instance.added' AND EXISTS (SELECT 1 FROM test_instance i
          WHERE i.tenant_id = audit_event.tenant_id AND i.project_id = audit_event.project_id AND i.id = audit_event.object_id)))
);

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        REVOKE UPDATE, DELETE, TRUNCATE ON execution_manifest FROM test365alm_runtime;
        REVOKE UPDATE, DELETE, TRUNCATE ON execution_manifest_step FROM test365alm_runtime;
        GRANT UPDATE (build_complete) ON execution_manifest TO test365alm_runtime;
        GRANT UPDATE (response_json) ON execution_idempotency TO test365alm_runtime;
    END IF;
END $$;
