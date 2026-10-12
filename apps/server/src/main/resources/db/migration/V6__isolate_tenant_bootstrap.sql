-- R03-M03-002 FIX02 follow-up.  V1-V5 are immutable.
-- Tenant/bootstrap data is provisioned by the migration/fixture owner.  The
-- runtime role may consume tenant membership for authorization but cannot
-- create or rewrite the bootstrap boundary.

ALTER TABLE tenant ADD COLUMN created_by UUID REFERENCES principal(id);
CREATE INDEX tenant_created_by_idx ON tenant (created_by);

CREATE OR REPLACE FUNCTION app_tenant_created_by(p_tenant_id UUID, p_principal_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT p_tenant_id IS NOT NULL AND p_principal_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM public.tenant t
        WHERE t.id = p_tenant_id AND t.created_by = p_principal_id
    )
$$;

REVOKE ALL ON FUNCTION public.app_tenant_created_by(uuid, uuid) FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        GRANT EXECUTE ON FUNCTION public.app_tenant_created_by(uuid, uuid) TO test365alm_runtime;
    END IF;
END $$;

DROP POLICY IF EXISTS tenant_insert ON tenant;
CREATE POLICY tenant_insert ON tenant FOR INSERT WITH CHECK (
    id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND created_by = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
    AND app_principal_enabled(created_by)
);

DROP POLICY IF EXISTS tenant_member_insert ON tenant_member;
CREATE POLICY tenant_member_insert ON tenant_member FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND (
        (
            principal_id::TEXT = NULLIF(current_setting('test365alm.principal_id', true), '')
            AND NOT app_tenant_has_member(tenant_member.tenant_id)
            AND app_tenant_created_by(tenant_member.tenant_id,
                NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
        )
        OR app_has_tenant_admin(tenant_id, NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
    )
);

-- Tenant and tenant-member bootstrap is an owner/fixture operation, not a
-- normal runtime capability.  Project/domain and project-member DML remain
-- explicitly granted by V3/V5 for the authorized project workflow.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        REVOKE INSERT, UPDATE, DELETE ON tenant FROM test365alm_runtime;
        REVOKE INSERT, UPDATE, DELETE ON tenant_member FROM test365alm_runtime;
    END IF;
END $$;
