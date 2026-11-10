-- Organization belongs to stable identities, never immutable revisions/manifests.
CREATE TABLE project_folder (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    id UUID PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('TEST_PLAN', 'TEST_LAB')),
    parent_id UUID,
    name TEXT NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 200),
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    row_version BIGINT NOT NULL DEFAULT 1 CHECK (row_version > 0),
    UNIQUE (tenant_id, project_id, kind, id),
    UNIQUE NULLS NOT DISTINCT (tenant_id, project_id, kind, parent_id, name),
    FOREIGN KEY (tenant_id, project_id) REFERENCES project(tenant_id, id),
    FOREIGN KEY (tenant_id, project_id, kind, parent_id)
        REFERENCES project_folder(tenant_id, project_id, kind, id)
);
CREATE TABLE folder_placement (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('TEST_PLAN', 'TEST_LAB')),
    object_id UUID NOT NULL,
    test_case_id UUID,
    test_set_id UUID,
    folder_id UUID,
    row_version BIGINT NOT NULL CHECK (row_version > 1),
    PRIMARY KEY (tenant_id, project_id, kind, object_id),
    CHECK ((kind='TEST_PLAN' AND test_case_id=object_id AND test_case_id IS NOT NULL AND test_set_id IS NULL)
        OR (kind='TEST_LAB' AND test_set_id=object_id AND test_set_id IS NOT NULL AND test_case_id IS NULL)),
    FOREIGN KEY (tenant_id, project_id, test_case_id) REFERENCES test_case(tenant_id, project_id, id),
    FOREIGN KEY (tenant_id, project_id, test_set_id) REFERENCES test_set(tenant_id, project_id, id),
    FOREIGN KEY (tenant_id, project_id, kind, folder_id) REFERENCES project_folder(tenant_id, project_id, kind, id)
);
CREATE INDEX folder_placement_directory ON folder_placement(tenant_id, project_id, kind, folder_id);
CREATE TABLE folder_idempotency (
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    principal_id UUID NOT NULL REFERENCES principal(id),
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    response_json TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP + INTERVAL '24 hours',
    PRIMARY KEY (tenant_id, project_id, principal_id, idempotency_key),
    FOREIGN KEY (tenant_id, project_id) REFERENCES project(tenant_id, id)
);
CREATE TABLE folder_outbox_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    actor_principal_id UUID NOT NULL REFERENCES principal(id),
    object_id UUID NOT NULL,
    object_version BIGINT NOT NULL,
    event_type TEXT NOT NULL CHECK (event_type IN ('folder.created','folder.updated','folder.deleted','folder.placed')),
    payload TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id, project_id) REFERENCES project(tenant_id, id)
);

CREATE FUNCTION app_folder_integrity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p UUID;
BEGIN
    IF TG_OP='DELETE' THEN p := OLD.project_id; ELSE p := NEW.project_id; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(p::text, 1));
    IF TG_TABLE_NAME='project_folder' THEN
        IF TG_OP='DELETE' THEN
            IF EXISTS (SELECT 1 FROM project_folder WHERE parent_id=OLD.id)
                OR EXISTS (SELECT 1 FROM folder_placement WHERE folder_id=OLD.id) THEN
                RAISE EXCEPTION 'folder is not empty' USING ERRCODE='23514';
            END IF;
            RETURN OLD;
        END IF;
        IF TG_OP='UPDATE' AND (NEW.id,NEW.tenant_id,NEW.project_id,NEW.kind)
            IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.project_id,OLD.kind) THEN
            RAISE EXCEPTION 'folder scope is immutable' USING ERRCODE='23514';
        END IF;
        IF EXISTS (WITH RECURSIVE ancestors AS (
            SELECT id,parent_id FROM project_folder WHERE id=NEW.parent_id
            UNION SELECT f.id,f.parent_id FROM project_folder f JOIN ancestors a ON f.id=a.parent_id
        ) SELECT 1 FROM ancestors WHERE id=NEW.id) OR NEW.parent_id=NEW.id THEN
            RAISE EXCEPTION 'folder cycle' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_folder_integrity BEFORE INSERT OR UPDATE OR DELETE ON project_folder
    FOR EACH ROW EXECUTE FUNCTION app_folder_integrity();
CREATE TRIGGER folder_placement_integrity BEFORE INSERT OR UPDATE ON folder_placement
    FOR EACH ROW EXECUTE FUNCTION app_folder_integrity();

DO $$ DECLARE t TEXT; BEGIN
    FOREACH t IN ARRAY ARRAY['project_folder','folder_placement','folder_idempotency','folder_outbox_event'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
        EXECUTE format($p$CREATE POLICY folder_read ON %I FOR SELECT USING (
            tenant_id::text=NULLIF(current_setting('test365alm.tenant_id',true),'')
            AND project_id::text=NULLIF(current_setting('test365alm.project_id',true),'')
            AND app_has_active_project_member(tenant_id,project_id,NULLIF(current_setting('test365alm.principal_id',true),'')::uuid))$p$,t);
        EXECUTE format($p$CREATE POLICY folder_write ON %I FOR ALL USING (
            tenant_id::text=NULLIF(current_setting('test365alm.tenant_id',true),'')
            AND project_id::text=NULLIF(current_setting('test365alm.project_id',true),'')
            AND app_has_active_project_writer(tenant_id,project_id,NULLIF(current_setting('test365alm.principal_id',true),'')::uuid))
            WITH CHECK (tenant_id::text=NULLIF(current_setting('test365alm.tenant_id',true),'')
            AND project_id::text=NULLIF(current_setting('test365alm.project_id',true),'')
            AND app_has_active_project_writer(tenant_id,project_id,NULLIF(current_setting('test365alm.principal_id',true),'')::uuid))$p$,t);
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='test365alm_runtime') THEN
        GRANT SELECT,INSERT,DELETE ON project_folder TO test365alm_runtime;
        GRANT UPDATE (parent_id,name,sort_order,row_version) ON project_folder TO test365alm_runtime;
        GRANT SELECT,INSERT ON folder_placement,folder_idempotency,folder_outbox_event TO test365alm_runtime;
        GRANT UPDATE (folder_id,row_version) ON folder_placement TO test365alm_runtime;
    END IF;
END $$;
CREATE POLICY folder_idempotency_actor ON folder_idempotency AS RESTRICTIVE FOR ALL
    USING (principal_id=NULLIF(current_setting('test365alm.principal_id',true),'')::uuid)
    WITH CHECK (principal_id=NULLIF(current_setting('test365alm.principal_id',true),'')::uuid);
CREATE POLICY folder_outbox_actor ON folder_outbox_event AS RESTRICTIVE FOR INSERT
    WITH CHECK (actor_principal_id=NULLIF(current_setting('test365alm.principal_id',true),'')::uuid);
CREATE POLICY folder_audit_insert ON audit_event FOR INSERT WITH CHECK (
    object_type='organization'
    AND tenant_id::text=NULLIF(current_setting('test365alm.tenant_id',true),'')
    AND project_id::text=NULLIF(current_setting('test365alm.project_id',true),'')
    AND actor_principal_id=NULLIF(current_setting('test365alm.principal_id',true),'')::uuid
    AND app_has_active_project_writer(tenant_id,project_id,actor_principal_id)
    AND EXISTS (SELECT 1 FROM folder_outbox_event e WHERE e.tenant_id=audit_event.tenant_id
        AND e.project_id=audit_event.project_id AND e.object_id=audit_event.object_id
        AND e.object_version=audit_event.object_revision AND e.event_type=audit_event.action
        AND e.actor_principal_id=audit_event.actor_principal_id)
);
