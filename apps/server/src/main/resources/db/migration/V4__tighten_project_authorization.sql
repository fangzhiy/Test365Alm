-- R03-M03-002 FIX01: tighten the V3 policies without rewriting V1-V3.
-- A tenant context is request scope, not proof of membership.  The runtime
-- role can set it, so every read policy also resolves the verified principal
-- against the membership tables.

CREATE OR REPLACE FUNCTION app_has_active_tenant_member(p_tenant_id UUID, p_principal_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT p_tenant_id IS NOT NULL AND p_principal_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM tenant_member tm
        WHERE tm.tenant_id = p_tenant_id
          AND tm.principal_id = p_principal_id
          AND tm.revoked_at IS NULL
          AND (tm.valid_until IS NULL OR tm.valid_until > CURRENT_TIMESTAMP)
    )
$$;

CREATE OR REPLACE FUNCTION app_has_active_project_member(p_tenant_id UUID, p_project_id UUID, p_principal_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT p_tenant_id IS NOT NULL AND p_project_id IS NOT NULL AND p_principal_id IS NOT NULL
       AND app_has_active_tenant_member(p_tenant_id, p_principal_id)
       AND EXISTS (
        SELECT 1 FROM project_member pm
        WHERE pm.tenant_id = p_tenant_id
          AND pm.project_id = p_project_id
          AND pm.principal_id = p_principal_id
          AND pm.revoked_at IS NULL
          AND (pm.valid_until IS NULL OR pm.valid_until > CURRENT_TIMESTAMP)
    )
$$;

CREATE OR REPLACE FUNCTION app_has_tenant_admin(p_tenant_id UUID, p_principal_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT app_has_active_tenant_member(p_tenant_id, p_principal_id)
       AND EXISTS (
        SELECT 1 FROM tenant_member tm
        WHERE tm.tenant_id = p_tenant_id
          AND tm.principal_id = p_principal_id
          AND tm.revoked_at IS NULL
          AND (tm.valid_until IS NULL OR tm.valid_until > CURRENT_TIMESTAMP)
          AND tm.roles && ARRAY['TENANT_ADMIN']::TEXT[]
    )
$$;

CREATE OR REPLACE FUNCTION app_has_any_project_admin(p_tenant_id UUID, p_principal_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT app_has_active_tenant_member(p_tenant_id, p_principal_id)
       AND EXISTS (
        SELECT 1 FROM project_member pm
        WHERE pm.tenant_id = p_tenant_id
          AND pm.principal_id = p_principal_id
          AND pm.revoked_at IS NULL
          AND (pm.valid_until IS NULL OR pm.valid_until > CURRENT_TIMESTAMP)
          AND pm.roles && ARRAY['PROJECT_ADMIN']::TEXT[]
    )
$$;

CREATE OR REPLACE FUNCTION app_has_project_admin(p_tenant_id UUID, p_project_id UUID, p_principal_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT app_has_active_project_member(p_tenant_id, p_project_id, p_principal_id)
       AND EXISTS (
        SELECT 1 FROM project_member pm
        WHERE pm.tenant_id = p_tenant_id
          AND pm.project_id = p_project_id
          AND pm.principal_id = p_principal_id
          AND pm.revoked_at IS NULL
          AND (pm.valid_until IS NULL OR pm.valid_until > CURRENT_TIMESTAMP)
          AND pm.roles && ARRAY['PROJECT_ADMIN']::TEXT[]
    )
$$;

CREATE OR REPLACE FUNCTION app_project_has_member(p_tenant_id UUID, p_project_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1 FROM project_member pm
        WHERE pm.tenant_id = p_tenant_id AND pm.project_id = p_project_id
    )
$$;

CREATE OR REPLACE FUNCTION app_project_created_by(p_tenant_id UUID, p_project_id UUID, p_principal_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1 FROM project p
        WHERE p.tenant_id = p_tenant_id
          AND p.id = p_project_id
          AND p.created_by = p_principal_id
    )
$$;

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        GRANT EXECUTE ON FUNCTION app_has_active_tenant_member(UUID, UUID) TO test365alm_runtime;
        GRANT EXECUTE ON FUNCTION app_has_active_project_member(UUID, UUID, UUID) TO test365alm_runtime;
        GRANT EXECUTE ON FUNCTION app_has_tenant_admin(UUID, UUID) TO test365alm_runtime;
        GRANT EXECUTE ON FUNCTION app_has_any_project_admin(UUID, UUID) TO test365alm_runtime;
        GRANT EXECUTE ON FUNCTION app_has_project_admin(UUID, UUID, UUID) TO test365alm_runtime;
        GRANT EXECUTE ON FUNCTION app_project_has_member(UUID, UUID) TO test365alm_runtime;
        GRANT EXECUTE ON FUNCTION app_project_created_by(UUID, UUID, UUID) TO test365alm_runtime;
    END IF;
END $$;

DROP POLICY tenant_scope ON tenant;
CREATE POLICY tenant_scope ON tenant USING (
    app_has_active_tenant_member(id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
) WITH CHECK (
    id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND NULLIF(current_setting('test365alm.principal_id', true), '') IS NOT NULL
);

DROP POLICY domain_scope ON domain;
CREATE POLICY domain_scope ON domain USING (
    app_has_active_tenant_member(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    AND tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
) WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND app_has_active_tenant_member(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);

DROP POLICY tenant_member_scope ON tenant_member;
CREATE POLICY tenant_member_scope ON tenant_member USING (
    app_has_active_tenant_member(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    AND (
        principal_id::TEXT = NULLIF(current_setting('test365alm.principal_id', true), '')
        OR (
            tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
            AND (
                app_has_tenant_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
                OR app_has_any_project_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
            )
        )
    )
) WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND (
        app_has_active_tenant_member(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
        OR principal_id::TEXT = NULLIF(current_setting('test365alm.principal_id', true), '')
    )
);

DROP POLICY project_scope ON project;
CREATE POLICY project_scope ON project USING (
    app_has_active_project_member(tenant_id, id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    AND (
        tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
        OR id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    )
) WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND app_has_active_tenant_member(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);

DROP POLICY project_member_scope ON project_member;
CREATE POLICY project_member_scope ON project_member USING (
    (
        tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
        OR project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    )
    AND (
        app_has_project_admin(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
        OR (
            principal_id = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
            AND app_has_active_tenant_member(tenant_id, principal_id)
        )
    )
) WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND app_has_active_tenant_member(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    AND (
        app_has_project_admin(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
        OR (
            NOT app_project_has_member(tenant_id, project_id)
            AND app_project_created_by(tenant_id, project_id,
                    NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
        )
    )
);

DROP POLICY audit_event_scope ON audit_event;
CREATE POLICY audit_event_scope ON audit_event USING (
    app_has_active_tenant_member(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    AND (
        tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
        OR project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    )
    AND (
        project_id IS NULL
        OR app_has_active_project_member(tenant_id, project_id,
                NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    )
) WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND app_has_active_tenant_member(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    AND (
        project_id IS NULL
        OR app_has_active_project_member(tenant_id, project_id,
                NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    )
);
