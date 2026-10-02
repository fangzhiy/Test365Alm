-- R04-M07-001-FIX01: close requirement scope and write-boundary gaps found
-- after V7.  Existing rows are validated before constraints are changed;
-- migration failure therefore leaves the schema and data untouched.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM requirement r
        LEFT JOIN requirement_revision rr
          ON rr.tenant_id = r.tenant_id
         AND rr.project_id = r.project_id
         AND rr.requirement_id = r.id
         AND rr.id = r.current_revision_id
        WHERE r.current_revision_id IS NOT NULL AND rr.id IS NULL
    ) THEN
        RAISE EXCEPTION 'requirement current revision is outside its tenant/project/requirement scope'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM outbox_event o
        LEFT JOIN requirement_revision rr
          ON rr.tenant_id = o.tenant_id
         AND rr.project_id = o.project_id
         AND rr.requirement_id = o.requirement_id
         AND rr.id = o.revision_id
        WHERE rr.id IS NULL
    ) THEN
        RAISE EXCEPTION 'outbox revision reference is outside its requirement scope'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM outbox_event o
        LEFT JOIN requirement r
          ON r.tenant_id = o.tenant_id
         AND r.project_id = o.project_id
         AND r.id = o.aggregate_id
        WHERE r.id IS NULL OR o.aggregate_id <> o.requirement_id
    ) THEN
        RAISE EXCEPTION 'outbox aggregate reference is outside its requirement scope'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM requirement_idempotency i
        LEFT JOIN requirement_revision rr
          ON rr.tenant_id = i.tenant_id
         AND rr.project_id = i.project_id
         AND rr.requirement_id = i.requirement_id
         AND rr.id = i.revision_id
        WHERE i.revision_id IS NOT NULL AND rr.id IS NULL
    ) THEN
        RAISE EXCEPTION 'idempotency revision reference is outside its requirement scope'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM requirement_idempotency i
        LEFT JOIN requirement r
          ON r.tenant_id = i.tenant_id
         AND r.project_id = i.project_id
         AND r.id = i.requirement_id
        WHERE i.requirement_id IS NOT NULL AND r.id IS NULL
    ) THEN
        RAISE EXCEPTION 'idempotency requirement reference is outside its tenant/project scope'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1 FROM requirement_idempotency
        WHERE (requirement_id IS NULL) <> (revision_id IS NULL)
    ) THEN
        RAISE EXCEPTION 'idempotency result must contain both requirement and revision references'
            USING ERRCODE = '23514';
    END IF;
END $$;

ALTER TABLE requirement_revision
    ADD CONSTRAINT requirement_revision_scope_requirement_unique
    UNIQUE (tenant_id, project_id, requirement_id, id);

ALTER TABLE requirement
    DROP CONSTRAINT IF EXISTS requirement_current_revision_fk;
ALTER TABLE requirement
    ADD CONSTRAINT requirement_current_revision_fk
    FOREIGN KEY (tenant_id, project_id, id, current_revision_id)
    REFERENCES requirement_revision (tenant_id, project_id, requirement_id, id);

ALTER TABLE outbox_event
    DROP CONSTRAINT IF EXISTS requirement_outbox_revision_fk;
ALTER TABLE outbox_event
    ADD CONSTRAINT requirement_outbox_revision_fk
    FOREIGN KEY (tenant_id, project_id, requirement_id, revision_id)
    REFERENCES requirement_revision (tenant_id, project_id, requirement_id, id);
ALTER TABLE outbox_event
    ADD CONSTRAINT requirement_outbox_aggregate_matches_requirement
    CHECK (aggregate_id = requirement_id);

ALTER TABLE requirement_idempotency
    DROP CONSTRAINT IF EXISTS requirement_idempotency_revision_fk;
ALTER TABLE requirement_idempotency
    ADD CONSTRAINT requirement_idempotency_revision_fk
    FOREIGN KEY (tenant_id, project_id, requirement_id, revision_id)
    REFERENCES requirement_revision (tenant_id, project_id, requirement_id, id);
ALTER TABLE requirement_idempotency
    ADD CONSTRAINT requirement_idempotency_result_pair
    CHECK ((requirement_id IS NULL) = (revision_id IS NULL));

-- The idempotency row is also the durable response record.  A replay must
-- return the result produced by the original request, even when the
-- requirement has since advanced to another revision.
ALTER TABLE requirement_idempotency
    ADD COLUMN result_display_number BIGINT,
    ADD COLUMN result_row_version BIGINT,
    ADD COLUMN result_revision_no BIGINT,
    ADD COLUMN result_title TEXT,
    ADD COLUMN result_body TEXT,
    ADD COLUMN result_priority TEXT,
    ADD COLUMN result_created_at TIMESTAMPTZ,
    ADD COLUMN result_created_by UUID;

-- V7 rows did not persist the response body.  Backfill from the referenced
-- immutable revision so upgrading an existing database also gets frozen
-- replay content; rows still in progress remain all-null and are handled by
-- the service's in-progress branch.
UPDATE requirement_idempotency i
SET result_display_number = r.display_number,
    result_row_version = r.row_version,
    result_revision_no = rr.revision_no,
    result_title = rr.title,
    result_body = rr.body,
    result_priority = rr.priority,
    result_created_at = r.created_at,
    result_created_by = r.created_by
FROM requirement r
JOIN requirement_revision rr
  ON rr.tenant_id = i.tenant_id
 AND rr.project_id = i.project_id
 AND rr.requirement_id = i.requirement_id
 AND rr.id = i.revision_id
WHERE i.requirement_id IS NOT NULL AND i.revision_id IS NOT NULL
  AND r.tenant_id = i.tenant_id AND r.project_id = i.project_id AND r.id = i.requirement_id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        REVOKE UPDATE, DELETE, TRUNCATE ON requirement_revision FROM test365alm_runtime;
        REVOKE UPDATE, DELETE, TRUNCATE ON outbox_event FROM test365alm_runtime;
        REVOKE TRUNCATE ON requirement_idempotency FROM test365alm_runtime;
        GRANT DELETE ON requirement_idempotency TO test365alm_runtime;
        GRANT UPDATE (
            requirement_id, revision_id, result_display_number, result_row_version,
            result_revision_no, result_title, result_body, result_priority,
            result_created_at, result_created_by
        ) ON requirement_idempotency TO test365alm_runtime;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION app_has_active_project_writer(
    p_tenant_id UUID, p_project_id UUID, p_principal_id UUID
) RETURNS BOOLEAN
LANGUAGE SQL STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT p_tenant_id IS NOT NULL AND p_project_id IS NOT NULL
       AND app_has_active_tenant_member(p_tenant_id, p_principal_id)
       AND EXISTS (
           SELECT 1 FROM public.project_member pm
           WHERE pm.tenant_id = p_tenant_id
             AND pm.project_id = p_project_id
             AND pm.principal_id = p_principal_id
             AND pm.revoked_at IS NULL
             AND (pm.valid_until IS NULL OR pm.valid_until > CURRENT_TIMESTAMP)
             AND pm.roles && ARRAY['PROJECT_ADMIN', 'PROJECT_MEMBER']::TEXT[]
       )
$$;

REVOKE ALL ON FUNCTION app_has_active_project_writer(UUID, UUID, UUID) FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        GRANT EXECUTE ON FUNCTION app_has_active_project_writer(UUID, UUID, UUID)
            TO test365alm_runtime;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION app_requirement_target(
    p_tenant_id UUID, p_project_id UUID, p_requirement_id UUID
) RETURNS BOOLEAN
LANGUAGE SQL STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT p_tenant_id IS NOT NULL AND p_project_id IS NOT NULL AND p_requirement_id IS NOT NULL
       AND EXISTS (
           SELECT 1 FROM public.requirement r
           WHERE r.tenant_id = p_tenant_id AND r.project_id = p_project_id AND r.id = p_requirement_id
       )
$$;

REVOKE ALL ON FUNCTION app_requirement_target(UUID, UUID, UUID) FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        GRANT EXECUTE ON FUNCTION app_requirement_target(UUID, UUID, UUID) TO test365alm_runtime;
    END IF;
END $$;

-- V7 write policies used any active project member, which included viewers.
DROP POLICY IF EXISTS requirement_allocator_scope ON requirement_number_allocator;
CREATE POLICY requirement_allocator_scope ON requirement_number_allocator
    USING (tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
       AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
       AND app_has_active_project_writer(tenant_id, project_id,
           NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
    WITH CHECK (tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
       AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
       AND app_has_active_project_writer(tenant_id, project_id,
           NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));

DROP POLICY IF EXISTS requirement_insert ON requirement;
CREATE POLICY requirement_insert ON requirement FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND created_by = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);
DROP POLICY IF EXISTS requirement_update ON requirement;
CREATE POLICY requirement_update ON requirement FOR UPDATE USING (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
) WITH CHECK (tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), ''));

DROP POLICY IF EXISTS requirement_revision_insert ON requirement_revision;
CREATE POLICY requirement_revision_insert ON requirement_revision FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND created_by = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);

DROP POLICY IF EXISTS requirement_outbox_insert ON outbox_event;
CREATE POLICY requirement_outbox_insert ON outbox_event FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);

DROP POLICY IF EXISTS requirement_idempotency_scope ON requirement_idempotency;
CREATE POLICY requirement_idempotency_scope ON requirement_idempotency
    USING (tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
       AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
       AND principal_id = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
       AND app_has_active_project_writer(tenant_id, project_id,
           NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
    WITH CHECK (tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
       AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
       AND principal_id = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
       AND app_has_active_project_writer(tenant_id, project_id,
           NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));
DROP POLICY IF EXISTS requirement_idempotency_delete ON requirement_idempotency;
CREATE POLICY requirement_idempotency_delete ON requirement_idempotency FOR DELETE USING (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND principal_id = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
    AND app_has_active_project_writer(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);

DROP POLICY IF EXISTS audit_event_insert ON audit_event;
CREATE POLICY audit_event_insert ON audit_event FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND app_has_active_tenant_member(tenant_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    AND (
        (project_id IS NULL AND app_has_tenant_admin(tenant_id,
            NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
        OR (project_id IS NOT NULL AND (
            app_has_tenant_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
            OR app_has_project_admin(tenant_id, project_id,
                NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
            OR (object_type = 'requirement' AND app_requirement_target(tenant_id, project_id, object_id)
                AND action IN ('requirement.created', 'requirement.updated')
                AND actor_principal_id = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
                AND app_has_active_project_writer(tenant_id, project_id,
                    NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
        ))
    )
);
