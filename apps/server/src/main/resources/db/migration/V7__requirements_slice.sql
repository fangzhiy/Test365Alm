-- R04-M07-001: the first requirement business slice.
-- V1-V6 are immutable.  Requirement revisions are append-only for the
-- restricted runtime role; the owner/migration role remains the only schema
-- owner.  All scope keys are repeated so cross-project references cannot be
-- represented by a single UUID.

CREATE TABLE requirement_number_allocator (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    project_id UUID NOT NULL,
    next_number BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, project_id),
    CONSTRAINT requirement_allocator_project_fk FOREIGN KEY (tenant_id, project_id)
        REFERENCES project (tenant_id, id),
    CONSTRAINT requirement_allocator_positive CHECK (next_number > 0)
);
CREATE TABLE requirement (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    id UUID PRIMARY KEY,
    display_number BIGINT NOT NULL,
    current_revision_id UUID,
    row_version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL REFERENCES principal(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT requirement_scope_unique UNIQUE (tenant_id, project_id, id),
    CONSTRAINT requirement_number_unique UNIQUE (tenant_id, project_id, display_number),
    CONSTRAINT requirement_number_positive CHECK (display_number > 0),
    CONSTRAINT requirement_version_positive CHECK (row_version > 0),
    CONSTRAINT requirement_project_fk FOREIGN KEY (tenant_id, project_id)
        REFERENCES project (tenant_id, id)
);

CREATE TABLE requirement_revision (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    id UUID PRIMARY KEY,
    requirement_id UUID NOT NULL,
    revision_no BIGINT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL DEFAULT '',
    priority TEXT NOT NULL DEFAULT 'MEDIUM',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL REFERENCES principal(id),
    CONSTRAINT requirement_revision_scope_unique UNIQUE (tenant_id, project_id, id),
    CONSTRAINT requirement_revision_requirement_fk FOREIGN KEY (tenant_id, project_id, requirement_id)
        REFERENCES requirement (tenant_id, project_id, id),
    CONSTRAINT requirement_revision_no_unique UNIQUE (tenant_id, project_id, requirement_id, revision_no),
    CONSTRAINT requirement_revision_no_positive CHECK (revision_no > 0),
    CONSTRAINT requirement_title_nonempty CHECK (length(btrim(title)) BETWEEN 1 AND 500),
    CONSTRAINT requirement_body_size CHECK (length(body) <= 100000),
    CONSTRAINT requirement_priority_valid CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

ALTER TABLE requirement
    ADD CONSTRAINT requirement_current_revision_fk
    FOREIGN KEY (tenant_id, project_id, current_revision_id)
    REFERENCES requirement_revision (tenant_id, project_id, id);

CREATE INDEX requirement_project_number_idx ON requirement (tenant_id, project_id, display_number);
CREATE INDEX requirement_revision_history_idx ON requirement_revision
    (tenant_id, project_id, requirement_id, revision_no DESC);

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    aggregate_id UUID NOT NULL,
    requirement_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    payload_version INTEGER NOT NULL DEFAULT 1,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    CONSTRAINT requirement_outbox_requirement_fk FOREIGN KEY (tenant_id, project_id, requirement_id)
        REFERENCES requirement (tenant_id, project_id, id),
    CONSTRAINT requirement_outbox_aggregate_fk FOREIGN KEY (tenant_id, project_id, aggregate_id)
        REFERENCES requirement (tenant_id, project_id, id),
    CONSTRAINT requirement_outbox_revision_fk FOREIGN KEY (tenant_id, project_id, revision_id)
        REFERENCES requirement_revision (tenant_id, project_id, id),
    CONSTRAINT requirement_outbox_type_nonempty CHECK (length(btrim(event_type)) > 0),
    CONSTRAINT requirement_outbox_version_positive CHECK (payload_version > 0)
);

CREATE INDEX requirement_outbox_unpublished_idx ON outbox_event (occurred_at)
    WHERE published_at IS NULL;

CREATE TABLE requirement_idempotency (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    principal_id UUID NOT NULL,
    route TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    requirement_id UUID,
    revision_id UUID,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, project_id, principal_id, route, idempotency_key),
    CONSTRAINT requirement_idempotency_requirement_fk FOREIGN KEY (tenant_id, project_id, requirement_id)
        REFERENCES requirement (tenant_id, project_id, id),
    CONSTRAINT requirement_idempotency_revision_fk FOREIGN KEY (tenant_id, project_id, revision_id)
        REFERENCES requirement_revision (tenant_id, project_id, id),
    CONSTRAINT requirement_idempotency_key_size CHECK (length(idempotency_key) BETWEEN 8 AND 128),
    CONSTRAINT requirement_idempotency_hash_nonempty CHECK (length(request_hash) = 64)
);

CREATE INDEX requirement_idempotency_expiry_idx ON requirement_idempotency (expires_at);

ALTER TABLE requirement_number_allocator ENABLE ROW LEVEL SECURITY;
ALTER TABLE requirement ENABLE ROW LEVEL SECURITY;
ALTER TABLE requirement_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE requirement_idempotency ENABLE ROW LEVEL SECURITY;

CREATE POLICY requirement_allocator_scope ON requirement_number_allocator
    USING (tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
       AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
       AND app_has_active_project_member(tenant_id, project_id,
           NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
    WITH CHECK (tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
       AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
       AND app_has_active_project_member(tenant_id, project_id,
           NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));

CREATE POLICY requirement_select ON requirement FOR SELECT USING (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);
CREATE POLICY requirement_insert ON requirement FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND created_by = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
    AND app_has_active_project_member(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);
CREATE POLICY requirement_update ON requirement FOR UPDATE USING (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
) WITH CHECK (tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), ''));

CREATE POLICY requirement_revision_select ON requirement_revision FOR SELECT USING (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);
CREATE POLICY requirement_revision_insert ON requirement_revision FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND created_by = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
    AND app_has_active_project_member(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);

CREATE POLICY requirement_outbox_select ON outbox_event FOR SELECT USING (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);
CREATE POLICY requirement_outbox_insert ON outbox_event FOR INSERT WITH CHECK (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND app_has_active_project_member(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
);

CREATE POLICY requirement_idempotency_scope ON requirement_idempotency USING (
    tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND principal_id = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
    AND app_has_active_project_member(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID)
) WITH CHECK (tenant_id::TEXT = NULLIF(current_setting('test365alm.tenant_id', true), '')
    AND project_id::TEXT = NULLIF(current_setting('test365alm.project_id', true), '')
    AND principal_id = NULLIF(current_setting('test365alm.principal_id', true), '')::UUID
    AND app_has_active_project_member(tenant_id, project_id,
        NULLIF(current_setting('test365alm.principal_id', true), '')::UUID));

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        GRANT SELECT, INSERT ON requirement_number_allocator TO test365alm_runtime;
        GRANT UPDATE (next_number) ON requirement_number_allocator TO test365alm_runtime;
        GRANT SELECT, INSERT ON requirement TO test365alm_runtime;
        -- Keep the stable identity and author fields immutable to the runtime
        -- role; only the service-controlled current pointer/version/timestamp
        -- may advance after a revision has been appended.
        GRANT UPDATE (current_revision_id, row_version, updated_at) ON requirement TO test365alm_runtime;
        GRANT SELECT, INSERT ON requirement_revision, outbox_event TO test365alm_runtime;
        GRANT SELECT, INSERT ON requirement_idempotency TO test365alm_runtime;
        GRANT UPDATE (requirement_id, revision_id) ON requirement_idempotency TO test365alm_runtime;
    END IF;
END $$;
-- Existing audit policy only permits project/tenant administrators.  Requirement
-- authors may append their own requirement audit intent, but cannot use this
-- exception for membership, tenant or project administration events.
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
            OR (action LIKE 'requirement.%' AND app_has_active_project_member(tenant_id, project_id,
                NULLIF(current_setting('test365alm.principal_id', true), '')::UUID))
        ))
    )
);
