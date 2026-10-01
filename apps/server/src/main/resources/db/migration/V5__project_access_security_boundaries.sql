-- R03-M03-002 FIX02: make the runtime authorization boundary explicit.
-- V1-V4 are immutable.  This migration replaces the permissive V3/V4
-- policies with command-specific policies and records membership deltas.

ALTER TABLE audit_event ADD COLUMN before_roles TEXT[];
ALTER TABLE audit_event ADD COLUMN after_roles TEXT[];
ALTER TABLE audit_event ADD COLUMN before_authorization_version BIGINT;
ALTER TABLE audit_event ADD COLUMN after_authorization_version BIGINT;

ALTER TABLE audit_event ADD CONSTRAINT audit_event_before_version_nonnegative
    CHECK (before_authorization_version IS NULL OR before_authorization_version >= 0);
ALTER TABLE audit_event ADD CONSTRAINT audit_event_after_version_nonnegative
    CHECK (after_authorization_version IS NULL OR after_authorization_version >= 0);

-- SECURITY DEFINER helpers are the only way for the non-owner runtime role to
-- inspect membership rows while RLS is active.  Their search path is fixed and
-- EXECUTE is not inherited by arbitrary roles through PUBLIC.
CREATE OR REPLACE FUNCTION app_principal_enabled(p_principal_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT p_principal_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM public.principal p
        WHERE p.id = p_principal_id AND p.disabled_at IS NULL
    )
$$;

CREATE OR REPLACE FUNCTION app_tenant_has_member(p_tenant_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT p_tenant_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM public.tenant_member tm WHERE tm.tenant_id = p_tenant_id
    )
$$;

CREATE OR REPLACE FUNCTION app_has_active_tenant_member(p_tenant_id UUID, p_principal_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT p_tenant_id IS NOT NULL AND app_principal_enabled(p_principal_id) AND EXISTS (
        SELECT 1 FROM public.tenant_member tm
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
    )
$$;

CREATE OR REPLACE FUNCTION app_has_tenant_admin(p_tenant_id UUID, p_principal_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT app_has_active_tenant_member(p_tenant_id, p_principal_id)
       AND EXISTS (
        SELECT 1 FROM public.tenant_member tm
        WHERE tm.tenant_id = p_tenant_id AND tm.principal_id = p_principal_id
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
SET search_path = pg_catalog, public
AS $$
    SELECT app_has_active_tenant_member(p_tenant_id, p_principal_id)
       AND EXISTS (
        SELECT 1 FROM public.project_member pm
        WHERE pm.tenant_id = p_tenant_id AND pm.principal_id = p_principal_id
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
SET search_path = pg_catalog, public
AS $$
    SELECT app_has_active_project_member(p_tenant_id, p_project_id, p_principal_id)
       AND EXISTS (
        SELECT 1 FROM public.project_member pm
        WHERE pm.tenant_id = p_tenant_id AND pm.project_id = p_project_id
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
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.project_member pm
        WHERE pm.tenant_id = p_tenant_id AND pm.project_id = p_project_id
    )
$$;

CREATE OR REPLACE FUNCTION app_project_created_by(p_tenant_id UUID, p_project_id UUID, p_principal_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.project p
        WHERE p.tenant_id = p_tenant_id AND p.id = p_project_id
          AND p.created_by = p_principal_id
    )
$$;

-- Remove inherited execution and re-grant only to the intended runtime role.
DO $$
DECLARE
    fn RECORD;
BEGIN
    FOR fn IN SELECT * FROM (VALUES
        ('app_principal_enabled(uuid)'::text),
        ('app_tenant_has_member(uuid)'::text),
        ('app_has_active_tenant_member(uuid,uuid)'::text),
        ('app_has_active_project_member(uuid,uuid,uuid)'::text),
        ('app_has_tenant_admin(uuid,uuid)'::text),
        ('app_has_any_project_admin(uuid,uuid)'::text),
        ('app_has_project_admin(uuid,uuid,uuid)'::text),
        ('app_project_has_member(uuid,uuid)'::text),
        ('app_project_created_by(uuid,uuid,uuid)'::text)
    ) AS f(signature) LOOP
        EXECUTE 'REVOKE ALL ON FUNCTION public.' || fn.signature || ' FROM PUBLIC';
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
            EXECUTE 'GRANT EXECUTE ON FUNCTION public.' || fn.signature || ' TO test365alm_runtime';
        END IF;
    END LOOP;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        REVOKE CREATE ON SCHEMA public FROM test365alm_runtime;
    END IF;
END $$;

-- The V4 policy names are removed before command-specific policies are added.
DROP POLICY IF EXISTS tenant_scope ON tenant;
DROP POLICY IF EXISTS domain_scope ON domain;
DROP POLICY IF EXISTS tenant_member_scope ON tenant_member;
DROP POLICY IF EXISTS project_scope ON project;
DROP POLICY IF EXISTS project_member_scope ON project_member;
DROP POLICY IF EXISTS audit_event_scope ON audit_event;

CREATE POLICY tenant_select ON tenant FOR SELECT USING (
    app_has_active_tenant_member(id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);
CREATE POLICY tenant_insert ON tenant FOR INSERT WITH CHECK (
    id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND app_principal_enabled(NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);
CREATE POLICY tenant_update ON tenant FOR UPDATE USING (
    app_has_tenant_admin(id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
) WITH CHECK (app_has_tenant_admin(id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));

CREATE POLICY domain_select ON domain FOR SELECT USING (
    app_has_active_tenant_member(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    AND tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
);
CREATE POLICY domain_insert ON domain FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND app_has_tenant_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);
CREATE POLICY domain_update ON domain FOR UPDATE USING (
    app_has_tenant_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
) WITH CHECK (app_has_tenant_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));

CREATE POLICY tenant_member_select ON tenant_member FOR SELECT USING (
    app_has_active_tenant_member(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    AND (
        principal_id::TEXT = NULLIF(current_setting('test365alm.principal_id', true), '')
        OR (
            tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
            AND (app_has_tenant_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
                 OR app_has_any_project_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
        )
    )
);
CREATE POLICY tenant_member_insert ON tenant_member FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND (
        (principal_id::TEXT = NULLIF(current_setting('test365alm.principal_id', true), '')
         AND NOT app_tenant_has_member(tenant_member.tenant_id))
        OR app_has_tenant_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    )
);
CREATE POLICY tenant_member_update ON tenant_member FOR UPDATE USING (
    app_has_tenant_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
) WITH CHECK (app_has_tenant_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));
CREATE POLICY tenant_member_delete ON tenant_member FOR DELETE USING (
    app_has_tenant_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);

CREATE POLICY project_select ON project FOR SELECT USING (
    app_has_active_project_member(tenant_id, id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    AND tenant_id::TEXT = COALESCE(NULLIF(current_setting('test365alm.tenant_id', true), ''), tenant_id::TEXT)
    AND id::TEXT = COALESCE(NULLIF(current_setting('test365alm.project_id', true), ''), id::TEXT)
);
CREATE POLICY project_insert ON project FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND app_has_tenant_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);
CREATE POLICY project_update ON project FOR UPDATE USING (
    app_has_project_admin(tenant_id, id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
) WITH CHECK (app_has_project_admin(tenant_id, id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));
CREATE POLICY project_delete ON project FOR DELETE USING (
    app_has_project_admin(tenant_id, id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);

CREATE POLICY project_member_select ON project_member FOR SELECT USING (
    tenant_id::TEXT = COALESCE(NULLIF(current_setting('test365alm.tenant_id', true), ''), tenant_id::TEXT)
    AND project_id::TEXT = COALESCE(NULLIF(current_setting('test365alm.project_id', true), ''), project_id::TEXT)
    AND (
        app_has_project_admin(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
        OR (principal_id = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
            AND app_has_active_tenant_member(tenant_id, principal_id))
    )
);
CREATE POLICY project_member_insert ON project_member FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND app_has_active_tenant_member(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    AND (
        app_has_project_admin(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
        OR (NOT app_project_has_member(tenant_id, project_id)
            AND app_project_created_by(tenant_id, project_id,
                NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
    )
);
CREATE POLICY project_member_update ON project_member FOR UPDATE USING (
    app_has_project_admin(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
) WITH CHECK (app_has_project_admin(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));
CREATE POLICY project_member_delete ON project_member FOR DELETE USING (
    app_has_project_admin(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);

CREATE POLICY audit_event_select ON audit_event FOR SELECT USING (
    app_has_active_project_member(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    AND tenant_id::TEXT = COALESCE(NULLIF(current_setting('test365alm.tenant_id', true), ''), tenant_id::TEXT)
    AND project_id::TEXT = COALESCE(NULLIF(current_setting('test365alm.project_id', true), ''), project_id::TEXT)
);
CREATE POLICY audit_event_insert ON audit_event FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND app_has_active_tenant_member(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    AND (
        (project_id IS NULL AND app_has_tenant_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
        OR (project_id IS NOT NULL AND (
            app_has_tenant_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
            OR app_has_project_admin(tenant_id, project_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
        ))
    )
);
