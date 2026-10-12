-- R08: additive project metadata and typed requirement -> test links only.
CREATE TABLE field_definition (
 tenant_id UUID NOT NULL, project_id UUID NOT NULL, id UUID PRIMARY KEY,
 field_key TEXT NOT NULL CHECK (field_key ~ '^[a-z][a-z0-9_]{0,63}$'),
 data_type TEXT NOT NULL CHECK (data_type IN ('TEXT','NUMBER','ENUM')),
 kind TEXT NOT NULL DEFAULT 'CUSTOM' CHECK (kind='CUSTOM'),
 row_version BIGINT NOT NULL CHECK(row_version>0),
 created_by UUID NOT NULL REFERENCES principal(id), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,project_id,id), UNIQUE(tenant_id,project_id,field_key),
 FOREIGN KEY(tenant_id,project_id) REFERENCES project(tenant_id,id),
 CHECK(field_key NOT IN ('id','title','body','priority','tenant_id','project_id','created_by','created_at','row_version','current_revision_id','test_type','steps','status'))
);
CREATE TABLE field_definition_revision (
 tenant_id UUID NOT NULL, project_id UUID NOT NULL, field_id UUID NOT NULL,
 revision_no BIGINT NOT NULL CHECK(revision_no>0), definition JSONB NOT NULL CHECK(jsonb_typeof(definition)='object'),
 created_by UUID NOT NULL REFERENCES principal(id), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,project_id,field_id,revision_no),
 FOREIGN KEY(tenant_id,project_id,field_id) REFERENCES field_definition(tenant_id,project_id,id)
);
ALTER TABLE field_definition ADD CONSTRAINT field_current_revision_fk
 FOREIGN KEY(tenant_id,project_id,id,row_version) REFERENCES field_definition_revision(tenant_id,project_id,field_id,revision_no)
 DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE trace_link (
 tenant_id UUID NOT NULL, project_id UUID NOT NULL, id UUID PRIMARY KEY,
 requirement_id UUID NOT NULL, test_case_id UUID NOT NULL,
 requirement_revision_id UUID NOT NULL, test_revision_id UUID NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('ACTIVE','INACTIVE')), row_version BIGINT NOT NULL CHECK(row_version>0),
 created_by UUID NOT NULL REFERENCES principal(id), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id) REFERENCES project(tenant_id,id),
 FOREIGN KEY(tenant_id,project_id,requirement_id) REFERENCES requirement(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id,test_case_id) REFERENCES test_case(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id,requirement_id,requirement_revision_id) REFERENCES requirement_revision(tenant_id,project_id,requirement_id,id),
 FOREIGN KEY(tenant_id,project_id,test_case_id,test_revision_id) REFERENCES test_revision(tenant_id,project_id,test_case_id,id)
);
CREATE UNIQUE INDEX trace_active_endpoint_unique ON trace_link(tenant_id,project_id,requirement_id,test_case_id) WHERE state='ACTIVE';
CREATE INDEX trace_requirement_page ON trace_link(tenant_id,project_id,requirement_id,id);
CREATE TABLE trace_link_revision (
 tenant_id UUID NOT NULL, project_id UUID NOT NULL, link_id UUID NOT NULL,
 revision_no BIGINT NOT NULL CHECK(revision_no>0), state TEXT NOT NULL CHECK(state IN ('ACTIVE','INACTIVE')),
 created_by UUID NOT NULL REFERENCES principal(id), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,project_id,link_id,revision_no),
 FOREIGN KEY(tenant_id,project_id,link_id) REFERENCES trace_link(tenant_id,project_id,id)
);
ALTER TABLE trace_link ADD CONSTRAINT trace_current_revision_fk
 FOREIGN KEY(tenant_id,project_id,id,row_version) REFERENCES trace_link_revision(tenant_id,project_id,link_id,revision_no)
 DEFERRABLE INITIALLY DEFERRED;

-- This narrowly scoped shared infrastructure serves these two slices only.
CREATE TABLE core_command_idempotency (
 tenant_id UUID NOT NULL, project_id UUID NOT NULL, principal_id UUID NOT NULL REFERENCES principal(id),
 route TEXT NOT NULL, idempotency_key TEXT NOT NULL CHECK(length(idempotency_key) BETWEEN 8 AND 128),
 request_hash TEXT NOT NULL CHECK(length(request_hash)=64), response_json JSONB NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL DEFAULT(CURRENT_TIMESTAMP+INTERVAL '24 hours'),
 PRIMARY KEY(tenant_id,project_id,principal_id,route,idempotency_key),
 FOREIGN KEY(tenant_id,project_id) REFERENCES project(tenant_id,id)
);
CREATE TABLE core_outbox_event (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, project_id UUID NOT NULL,
 field_id UUID, link_id UUID, object_revision BIGINT NOT NULL, event_type TEXT NOT NULL,
 payload_version INTEGER NOT NULL DEFAULT 1 CHECK(payload_version=1), payload JSONB NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK((field_id IS NOT NULL)::INTEGER+(link_id IS NOT NULL)::INTEGER=1),
 FOREIGN KEY(tenant_id,project_id,field_id) REFERENCES field_definition(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id,link_id) REFERENCES trace_link(tenant_id,project_id,id)
);
CREATE FUNCTION app_core_scope(t UUID,p UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
 SELECT t::TEXT=NULLIF(current_setting('test365alm.tenant_id',true),'')
 AND p::TEXT=NULLIF(current_setting('test365alm.project_id',true),'')
 AND app_has_active_project_member(t,p,NULLIF(current_setting('test365alm.principal_id',true),'')::UUID)
$$;
REVOKE ALL ON FUNCTION app_core_scope(UUID,UUID) FROM PUBLIC;

DO $$ DECLARE tbl TEXT; BEGIN
 FOREACH tbl IN ARRAY ARRAY['field_definition','field_definition_revision','trace_link','trace_link_revision','core_outbox_event'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY scoped_read ON %I FOR SELECT USING(app_core_scope(tenant_id,project_id))',tbl);
 END LOOP;
 FOREACH tbl IN ARRAY ARRAY['field_definition','field_definition_revision'] LOOP
  EXECUTE format('CREATE POLICY scoped_insert ON %I FOR INSERT WITH CHECK(app_core_scope(tenant_id,project_id) AND created_by=NULLIF(current_setting(''test365alm.principal_id'',true),'''')::UUID AND app_has_project_admin(tenant_id,project_id,created_by))',tbl);
 END LOOP;
 FOREACH tbl IN ARRAY ARRAY['trace_link','trace_link_revision'] LOOP
  EXECUTE format('CREATE POLICY scoped_insert ON %I FOR INSERT WITH CHECK(app_core_scope(tenant_id,project_id) AND created_by=NULLIF(current_setting(''test365alm.principal_id'',true),'''')::UUID AND app_has_active_project_writer(tenant_id,project_id,created_by))',tbl);
 END LOOP;
END $$;
ALTER TABLE core_command_idempotency ENABLE ROW LEVEL SECURITY;
CREATE POLICY field_update ON field_definition FOR UPDATE
 USING(app_core_scope(tenant_id,project_id) AND app_has_project_admin(tenant_id,project_id,NULLIF(current_setting('test365alm.principal_id',true),'')::UUID))
 WITH CHECK(app_core_scope(tenant_id,project_id) AND app_has_project_admin(tenant_id,project_id,NULLIF(current_setting('test365alm.principal_id',true),'')::UUID));
CREATE POLICY trace_update ON trace_link FOR UPDATE
 USING(app_core_scope(tenant_id,project_id) AND app_has_active_project_writer(tenant_id,project_id,NULLIF(current_setting('test365alm.principal_id',true),'')::UUID))
 WITH CHECK(app_core_scope(tenant_id,project_id) AND app_has_active_project_writer(tenant_id,project_id,NULLIF(current_setting('test365alm.principal_id',true),'')::UUID));
CREATE POLICY core_idem_read ON core_command_idempotency FOR SELECT
 USING(app_core_scope(tenant_id,project_id) AND principal_id=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID AND app_has_active_project_writer(tenant_id,project_id,principal_id))
;
CREATE POLICY core_idem_insert ON core_command_idempotency FOR INSERT
 WITH CHECK(app_core_scope(tenant_id,project_id) AND principal_id=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID AND app_has_active_project_writer(tenant_id,project_id,principal_id));
CREATE POLICY core_idem_expire ON core_command_idempotency FOR DELETE
 USING(app_core_scope(tenant_id,project_id) AND principal_id=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID AND expires_at<=CURRENT_TIMESTAMP AND app_has_active_project_writer(tenant_id,project_id,principal_id));
CREATE POLICY core_outbox_insert ON core_outbox_event FOR INSERT WITH CHECK(
 app_core_scope(tenant_id,project_id) AND app_has_active_project_writer(tenant_id,project_id,NULLIF(current_setting('test365alm.principal_id',true),'')::UUID)
 AND (field_id IS NULL OR app_has_project_admin(tenant_id,project_id,NULLIF(current_setting('test365alm.principal_id',true),'')::UUID)));
CREATE POLICY core_audit_insert ON audit_event FOR INSERT WITH CHECK(
 app_core_scope(tenant_id,project_id) AND actor_principal_id=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID
 AND ((object_type='field_definition' AND action IN ('field.created','field.revised') AND app_has_project_admin(tenant_id,project_id,actor_principal_id)
  AND EXISTS(SELECT 1 FROM field_definition f WHERE f.tenant_id=audit_event.tenant_id AND f.project_id=audit_event.project_id AND f.id=audit_event.object_id))
 OR (object_type='trace_link' AND action IN ('trace.created','trace.invalidated') AND app_has_active_project_writer(tenant_id,project_id,actor_principal_id)
  AND EXISTS(SELECT 1 FROM trace_link l WHERE l.tenant_id=audit_event.tenant_id AND l.project_id=audit_event.project_id AND l.id=audit_event.object_id))));
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='test365alm_runtime') THEN
 GRANT EXECUTE ON FUNCTION app_core_scope(UUID,UUID) TO test365alm_runtime;
 GRANT SELECT,INSERT ON field_definition,field_definition_revision,trace_link,trace_link_revision,core_command_idempotency,core_outbox_event TO test365alm_runtime;
 GRANT UPDATE(row_version) ON field_definition TO test365alm_runtime;
 GRANT UPDATE(row_version,state) ON trace_link TO test365alm_runtime;
 GRANT DELETE ON core_command_idempotency TO test365alm_runtime;
END IF; END $$;
