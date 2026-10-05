-- R06-M09-001-FIX03.  Tighten the execution-builder boundary without
-- changing V1-V14.  The transaction-local flag permits the service to build a
-- new manifest/attempt, but it does not make arbitrary terminal rows valid.

CREATE OR REPLACE FUNCTION execution_manifest_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF COALESCE(current_setting('test365alm.execution_building', true), 'false') <> 'true'
           OR NEW.build_complete IS DISTINCT FROM FALSE THEN
            RAISE EXCEPTION 'execution manifest must start as an incomplete build' USING ERRCODE = '55006';
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

CREATE OR REPLACE FUNCTION execution_attempt_terminal_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF COALESCE(current_setting('test365alm.execution_building', true), 'false') <> 'true'
           OR NEW.status <> 'RUNNING'
           OR NEW.conclusion IS NOT NULL
           OR NEW.finished_at IS NOT NULL
           OR NEW.row_version <> 1 THEN
            RAISE EXCEPTION 'new attempts must start RUNNING with version 1' USING ERRCODE = '55006';
        END IF;
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

CREATE OR REPLACE FUNCTION execution_step_terminal_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE state TEXT;
DECLARE bound_manifest UUID;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF COALESCE(current_setting('test365alm.execution_building', true), 'false') <> 'true'
           OR NEW.row_version <> 1
           OR NEW.actual_result <> ''
           OR NEW.conclusion <> 'NOT_RUN'
           OR NEW.updated_by IS NOT NULL THEN
            RAISE EXCEPTION 'new run steps must start NOT_RUN with version 1' USING ERRCODE = '55006';
        END IF;
        SELECT a.status, a.manifest_id INTO state, bound_manifest
        FROM run_attempt a
        WHERE a.tenant_id = NEW.tenant_id AND a.project_id = NEW.project_id AND a.id = NEW.attempt_id;
        IF state IS DISTINCT FROM 'RUNNING' OR bound_manifest IS DISTINCT FROM NEW.manifest_id THEN
            RAISE EXCEPTION 'new run step must belong to a running attempt manifest' USING ERRCODE = '55006';
        END IF;
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
