-- R05-M08-001-FIX01: seal every test revision after its complete step
-- snapshot has been written.  V10 remains immutable; existing rows are
-- treated as already sealed and only a new revision build may insert steps.

ALTER TABLE test_revision ADD COLUMN sealed_at TIMESTAMPTZ;

-- V10 rows were committed with their complete step snapshots already in
-- place.  Preserve their original creation time as the best available
-- sealing evidence while leaving newly inserted rows open until the service
-- seals them after all steps have been written.
UPDATE test_revision SET sealed_at = created_at WHERE sealed_at IS NULL;

DROP POLICY IF EXISTS test_revision_insert ON test_revision;
CREATE POLICY test_revision_insert ON test_revision FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND created_by = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
    AND sealed_at IS NULL
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));

-- Only the writer that created an open revision may close it.  There is no
-- policy path from sealed_at != NULL back to NULL, so runtime connections
-- cannot reopen historical or current step collections.
CREATE POLICY test_revision_seal ON test_revision FOR UPDATE USING (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND sealed_at IS NULL
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND sealed_at IS NOT NULL
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));

DROP POLICY IF EXISTS test_step_insert ON test_step;
CREATE OR REPLACE FUNCTION app_test_revision_step_open(p_tenant UUID, p_project UUID,
        p_test_case UUID, p_revision UUID)
RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public
AS $$
    -- Keep the existing composite-FK diagnostics for a foreign target while
    -- rejecting inserts into a real, already sealed revision at the RLS
    -- boundary.  The function runs with a fixed search path and does not
    -- grant callers any write capability.
    SELECT CASE WHEN EXISTS (
        SELECT 1 FROM test_revision r
         WHERE r.tenant_id = p_tenant
           AND r.project_id = p_project
           AND r.test_case_id = p_test_case
           AND r.id = p_revision
    ) THEN EXISTS (
        SELECT 1 FROM test_revision r
         WHERE r.tenant_id = p_tenant
           AND r.project_id = p_project
           AND r.test_case_id = p_test_case
           AND r.id = p_revision
           AND r.sealed_at IS NULL
    ) ELSE TRUE END
$$;
REVOKE ALL ON FUNCTION app_test_revision_step_open(UUID, UUID, UUID, UUID) FROM PUBLIC;

CREATE POLICY test_step_insert ON test_step FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    AND app_test_revision_step_open(tenant_id, project_id, test_case_id, revision_id));

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        GRANT EXECUTE ON FUNCTION app_test_revision_step_open(UUID, UUID, UUID, UUID)
            TO test365alm_runtime;
        GRANT UPDATE (sealed_at) ON test_revision TO test365alm_runtime;
    END IF;
END $$;
