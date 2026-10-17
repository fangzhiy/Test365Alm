-- R10 additive planning slice. No changes to V1-V18 or existing business rows.
CREATE TABLE release_number_allocator (
 tenant_id UUID NOT NULL, project_id UUID NOT NULL, next_number BIGINT NOT NULL DEFAULT 1 CHECK(next_number>0),
 PRIMARY KEY(tenant_id,project_id), FOREIGN KEY(tenant_id,project_id) REFERENCES project(tenant_id,id)
);
CREATE TABLE release_plan (
 tenant_id UUID NOT NULL, project_id UUID NOT NULL, id UUID PRIMARY KEY,
 display_number BIGINT NOT NULL CHECK(display_number>0), name TEXT NOT NULL CHECK(length(btrim(name)) BETWEEN 1 AND 200),
 description TEXT NOT NULL DEFAULT '' CHECK(length(description)<=10000), start_date DATE NOT NULL, end_date DATE NOT NULL,
 status TEXT NOT NULL CHECK(status IN('PLANNED','ACTIVE','CLOSED','ARCHIVED')), row_version BIGINT NOT NULL CHECK(row_version>0),
 scope_version BIGINT NOT NULL DEFAULT 0 CHECK(scope_version>=0), created_by UUID NOT NULL REFERENCES principal(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,project_id,id), UNIQUE(tenant_id,project_id,display_number),
 FOREIGN KEY(tenant_id,project_id) REFERENCES project(tenant_id,id), CHECK(start_date<=end_date),
 CHECK(start_date>=DATE '0001-01-01' AND end_date<=DATE '9999-12-31')
);
CREATE TABLE release_cycle (
 tenant_id UUID NOT NULL, project_id UUID NOT NULL, release_id UUID NOT NULL, id UUID PRIMARY KEY,
 name TEXT NOT NULL CHECK(length(btrim(name)) BETWEEN 1 AND 200), start_date DATE NOT NULL, end_date DATE NOT NULL,
 status TEXT NOT NULL CHECK(status IN('PLANNED','ACTIVE','CLOSED')), row_version BIGINT NOT NULL CHECK(row_version>0),
 created_by UUID NOT NULL REFERENCES principal(id), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,project_id,release_id,id),
 FOREIGN KEY(tenant_id,project_id,release_id) REFERENCES release_plan(tenant_id,project_id,id), CHECK(start_date<=end_date)
);
CREATE TABLE release_scope (
 tenant_id UUID NOT NULL, project_id UUID NOT NULL, release_id UUID NOT NULL, id UUID PRIMARY KEY, cycle_id UUID,
 kind TEXT NOT NULL CHECK(kind IN('REQUIREMENT','TEST_CASE')),
 requirement_id UUID, requirement_revision_id UUID, test_case_id UUID, test_revision_id UUID,
 created_by UUID NOT NULL REFERENCES principal(id), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(tenant_id,project_id,release_id) REFERENCES release_plan(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id,release_id,cycle_id) REFERENCES release_cycle(tenant_id,project_id,release_id,id),
 FOREIGN KEY(tenant_id,project_id,requirement_id,requirement_revision_id) REFERENCES requirement_revision(tenant_id,project_id,requirement_id,id),
 FOREIGN KEY(tenant_id,project_id,test_case_id,test_revision_id) REFERENCES test_revision(tenant_id,project_id,test_case_id,id),
 CHECK((kind='REQUIREMENT' AND requirement_id IS NOT NULL AND requirement_revision_id IS NOT NULL AND test_case_id IS NULL AND test_revision_id IS NULL)
 OR(kind='TEST_CASE' AND test_case_id IS NOT NULL AND test_revision_id IS NOT NULL AND requirement_id IS NULL AND requirement_revision_id IS NULL)),
 UNIQUE NULLS NOT DISTINCT(tenant_id,project_id,release_id,cycle_id,requirement_id,test_case_id)
);
CREATE TABLE quality_snapshot (
 tenant_id UUID NOT NULL, project_id UUID NOT NULL, release_id UUID NOT NULL, id UUID PRIMARY KEY,
 evaluated_by UUID NOT NULL REFERENCES principal(id), evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 metric_version INTEGER NOT NULL CHECK(metric_version=1), rule TEXT NOT NULL CHECK(rule='RELEASE_FROZEN_SCOPE_LATEST_FINISHED_NO_ACTIVE_V1'),
 scope_version BIGINT NOT NULL CHECK(scope_version>=0), scope_fingerprint TEXT NOT NULL CHECK(scope_fingerprint~'^[0-9a-f]{64}$'),
 requirement_count INTEGER NOT NULL CHECK(requirement_count BETWEEN 0 AND 1000), coverage_count INTEGER NOT NULL CHECK(coverage_count BETWEEN 0 AND requirement_count),
 planned_count INTEGER NOT NULL CHECK(planned_count BETWEEN 0 AND 1000), completed_count INTEGER NOT NULL CHECK(completed_count BETWEEN 0 AND planned_count),
 pass_count INTEGER NOT NULL CHECK(pass_count BETWEEN 0 AND completed_count), related_defect_count INTEGER NOT NULL CHECK(related_defect_count>=0),
 open_defect_count INTEGER NOT NULL CHECK(open_defect_count BETWEEN 0 AND related_defect_count), evidence JSONB NOT NULL CHECK(jsonb_typeof(evidence)='object'),
 UNIQUE(tenant_id,project_id,release_id,id), FOREIGN KEY(tenant_id,project_id,release_id) REFERENCES release_plan(tenant_id,project_id,id)
);
CREATE TABLE release_outbox_event (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, project_id UUID NOT NULL, release_id UUID NOT NULL,
 cycle_id UUID, snapshot_id UUID, object_version BIGINT NOT NULL CHECK(object_version>0),
 event_type TEXT NOT NULL CHECK(event_type IN('RELEASE_CREATED','RELEASE_UPDATED','RELEASE_ARCHIVED','CYCLE_CREATED','CYCLE_UPDATED','RELEASE_SCOPE_ADDED','RELEASE_SCOPE_REMOVED','QUALITY_SNAPSHOT_CREATED')),
 payload_version INTEGER NOT NULL DEFAULT 1 CHECK(payload_version=1), payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='object'),
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(tenant_id,project_id,release_id) REFERENCES release_plan(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id,release_id,cycle_id) REFERENCES release_cycle(tenant_id,project_id,release_id,id),
 FOREIGN KEY(tenant_id,project_id,release_id,snapshot_id) REFERENCES quality_snapshot(tenant_id,project_id,release_id,id)
);
CREATE INDEX release_cycle_page ON release_cycle(tenant_id,project_id,release_id,id);
CREATE INDEX release_scope_page ON release_scope(tenant_id,project_id,release_id,id);
CREATE INDEX quality_snapshot_page ON quality_snapshot(tenant_id,project_id,release_id,id);

CREATE FUNCTION release_plan_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'PLANNED' OR NEW.row_version<>1 OR NEW.scope_version<>0 THEN
   RAISE EXCEPTION 'release initial state must be PLANNED version 1 empty scope' USING ERRCODE='23514'; END IF;
 ELSE
  IF OLD.status='ARCHIVED' THEN RAISE EXCEPTION 'archived release is read-only' USING ERRCODE='55006'; END IF;
  IF NEW.status<>OLD.status AND (OLD.status||':'||NEW.status) NOT IN('PLANNED:ACTIVE','PLANNED:CLOSED','PLANNED:ARCHIVED','ACTIVE:CLOSED','ACTIVE:ARCHIVED','CLOSED:ARCHIVED') THEN
   RAISE EXCEPTION 'invalid release transition' USING ERRCODE='23514'; END IF;
  IF OLD.status='CLOSED' AND (NEW.status<>'ARCHIVED' OR ROW(NEW.name,NEW.description,NEW.start_date,NEW.end_date,NEW.scope_version) IS DISTINCT FROM ROW(OLD.name,OLD.description,OLD.start_date,OLD.end_date,OLD.scope_version)) THEN
   RAISE EXCEPTION 'closed release content is read-only' USING ERRCODE='55006'; END IF;
  IF NEW.scope_version<>OLD.scope_version THEN
   IF NEW.scope_version<>OLD.scope_version+1 OR NEW.row_version<>OLD.row_version OR ROW(NEW.name,NEW.description,NEW.start_date,NEW.end_date,NEW.status) IS DISTINCT FROM ROW(OLD.name,OLD.description,OLD.start_date,OLD.end_date,OLD.status) THEN
    RAISE EXCEPTION 'scope version advances separately from release content' USING ERRCODE='23514'; END IF;
  ELSIF NEW.row_version<>OLD.row_version+1 THEN RAISE EXCEPTION 'release version must advance once' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM release_cycle c WHERE c.tenant_id=NEW.tenant_id AND c.project_id=NEW.project_id AND c.release_id=NEW.id AND (c.start_date<NEW.start_date OR c.end_date>NEW.end_date)) THEN
   RAISE EXCEPTION 'cycle would lie outside release dates' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER release_plan_rules BEFORE INSERT OR UPDATE ON release_plan FOR EACH ROW EXECUTE FUNCTION release_plan_guard();
CREATE FUNCTION release_cycle_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent release_plan;
BEGIN
 SELECT * INTO parent FROM release_plan WHERE tenant_id=NEW.tenant_id AND project_id=NEW.project_id AND id=NEW.release_id FOR UPDATE;
 IF parent.id IS NULL THEN RAISE EXCEPTION 'release scope not found' USING ERRCODE='23503'; END IF;
 IF parent.status NOT IN('PLANNED','ACTIVE') THEN RAISE EXCEPTION 'release is read-only' USING ERRCODE='55006'; END IF;
 IF NEW.start_date<parent.start_date OR NEW.end_date>parent.end_date THEN RAISE EXCEPTION 'cycle outside release dates' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'PLANNED' OR NEW.row_version<>1 THEN RAISE EXCEPTION 'cycle initial state must be PLANNED version 1' USING ERRCODE='23514'; END IF;
 ELSE
  IF OLD.status='CLOSED' THEN RAISE EXCEPTION 'closed cycle is read-only' USING ERRCODE='55006'; END IF;
  IF NEW.row_version<>OLD.row_version+1 THEN RAISE EXCEPTION 'cycle version must advance once' USING ERRCODE='23514'; END IF;
  IF NEW.status<>OLD.status AND (OLD.status||':'||NEW.status) NOT IN('PLANNED:ACTIVE','PLANNED:CLOSED','ACTIVE:CLOSED') THEN RAISE EXCEPTION 'invalid cycle transition' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER release_cycle_rules BEFORE INSERT OR UPDATE ON release_cycle FOR EACH ROW EXECUTE FUNCTION release_cycle_guard();
CREATE FUNCTION release_scope_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE item release_scope; parent release_plan;
BEGIN
 IF TG_OP='DELETE' THEN item:=OLD; ELSE item:=NEW; END IF;
 SELECT * INTO parent FROM release_plan WHERE tenant_id=item.tenant_id AND project_id=item.project_id AND id=item.release_id FOR UPDATE;
 IF parent.id IS NULL THEN RAISE EXCEPTION 'release scope not found' USING ERRCODE='23503'; END IF;
 IF parent.status NOT IN('PLANNED','ACTIVE') THEN RAISE EXCEPTION 'release scope is read-only' USING ERRCODE='55006'; END IF;
 IF item.cycle_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM release_cycle c WHERE c.tenant_id=item.tenant_id AND c.project_id=item.project_id AND c.release_id=item.release_id AND c.id=item.cycle_id AND c.status IN('PLANNED','ACTIVE')) THEN
  RAISE EXCEPTION 'cycle scope is absent or read-only' USING ERRCODE='55006'; END IF;
 IF TG_OP='INSERT' THEN
  IF (SELECT count(*) FROM release_scope s WHERE s.tenant_id=item.tenant_id AND s.project_id=item.project_id AND s.release_id=item.release_id)>=1000 THEN RAISE EXCEPTION 'release scope limit 1000' USING ERRCODE='23514'; END IF;
  IF item.kind='REQUIREMENT' AND NOT EXISTS(SELECT 1 FROM requirement r WHERE r.tenant_id=item.tenant_id AND r.project_id=item.project_id AND r.id=item.requirement_id AND r.current_revision_id=item.requirement_revision_id AND r.deleted_at IS NULL) THEN
   RAISE EXCEPTION 'requirement scope must freeze current revision' USING ERRCODE='23514'; END IF;
  IF item.kind='TEST_CASE' AND NOT EXISTS(SELECT 1 FROM test_case t JOIN test_revision r ON r.id=t.current_revision_id WHERE t.tenant_id=item.tenant_id AND t.project_id=item.project_id AND t.id=item.test_case_id AND t.current_revision_id=item.test_revision_id AND r.sealed_at IS NOT NULL) THEN
   RAISE EXCEPTION 'test scope must freeze current sealed revision' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM release_scope s WHERE s.tenant_id=item.tenant_id AND s.project_id=item.project_id AND s.release_id=item.release_id AND
   ((s.requirement_id=item.requirement_id AND s.requirement_revision_id<>item.requirement_revision_id) OR(s.test_case_id=item.test_case_id AND s.test_revision_id<>item.test_revision_id))) THEN
   RAISE EXCEPTION 'release scope revision conflict' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN item;
END $$;
CREATE TRIGGER release_scope_rules BEFORE INSERT OR DELETE ON release_scope FOR EACH ROW EXECUTE FUNCTION release_scope_guard();
CREATE FUNCTION release_scope_version_bump() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE item release_scope;
BEGIN
 IF TG_OP='DELETE' THEN item:=OLD; ELSE item:=NEW; END IF;
 UPDATE release_plan SET scope_version=scope_version+1,updated_at=CURRENT_TIMESTAMP
 WHERE tenant_id=item.tenant_id AND project_id=item.project_id AND id=item.release_id;
 RETURN item;
END $$;
CREATE TRIGGER release_scope_version AFTER INSERT OR DELETE ON release_scope FOR EACH ROW EXECUTE FUNCTION release_scope_version_bump();
CREATE FUNCTION quality_snapshot_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'quality snapshot is immutable' USING ERRCODE='55006'; END IF;
 IF NOT EXISTS(SELECT 1 FROM release_plan r WHERE r.tenant_id=NEW.tenant_id AND r.project_id=NEW.project_id AND r.id=NEW.release_id AND r.status<>'ARCHIVED' AND r.scope_version=NEW.scope_version) THEN
  RAISE EXCEPTION 'snapshot release is archived or scope version mismatches' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER quality_snapshot_immutable BEFORE INSERT OR UPDATE OR DELETE ON quality_snapshot FOR EACH ROW EXECUTE FUNCTION quality_snapshot_guard();

DO $$ DECLARE tbl TEXT; BEGIN
 FOREACH tbl IN ARRAY ARRAY['release_plan','release_cycle','release_scope','quality_snapshot','release_outbox_event','release_number_allocator'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY release_read ON %I FOR SELECT USING(app_core_scope(tenant_id,project_id))',tbl);
  EXECUTE format('CREATE POLICY release_insert ON %I FOR INSERT WITH CHECK(app_core_scope(tenant_id,project_id) AND app_has_project_admin(tenant_id,project_id,NULLIF(current_setting(''test365alm.principal_id'',true),'''')::UUID))',tbl);
 END LOOP;
 FOREACH tbl IN ARRAY ARRAY['release_plan','release_cycle','release_scope'] LOOP
  EXECUTE format('CREATE POLICY release_actor ON %I AS RESTRICTIVE FOR INSERT WITH CHECK(created_by=NULLIF(current_setting(''test365alm.principal_id'',true),'''')::UUID)',tbl);
 END LOOP;
 FOREACH tbl IN ARRAY ARRAY['release_plan','release_cycle','release_number_allocator'] LOOP
  EXECUTE format('CREATE POLICY release_update ON %I FOR UPDATE USING(app_core_scope(tenant_id,project_id) AND app_has_project_admin(tenant_id,project_id,NULLIF(current_setting(''test365alm.principal_id'',true),'''')::UUID)) WITH CHECK(app_core_scope(tenant_id,project_id) AND app_has_project_admin(tenant_id,project_id,NULLIF(current_setting(''test365alm.principal_id'',true),'''')::UUID))',tbl);
 END LOOP;
END $$;
CREATE POLICY release_scope_delete ON release_scope FOR DELETE USING(app_core_scope(tenant_id,project_id) AND app_has_project_admin(tenant_id,project_id,NULLIF(current_setting('test365alm.principal_id',true),'')::UUID));
CREATE POLICY snapshot_actor ON quality_snapshot AS RESTRICTIVE FOR INSERT WITH CHECK(evaluated_by=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID);
CREATE POLICY release_audit_insert ON audit_event FOR INSERT WITH CHECK(app_core_scope(tenant_id,project_id)
 AND actor_principal_id=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID AND app_has_project_admin(tenant_id,project_id,actor_principal_id)
 AND object_type='release_plan' AND action IN('RELEASE_CREATED','RELEASE_UPDATED','RELEASE_ARCHIVED','CYCLE_CREATED','CYCLE_UPDATED','RELEASE_SCOPE_ADDED','RELEASE_SCOPE_REMOVED','QUALITY_SNAPSHOT_CREATED')
 AND EXISTS(SELECT 1 FROM release_plan r WHERE r.tenant_id=audit_event.tenant_id AND r.project_id=audit_event.project_id AND r.id=audit_event.object_id));
-- Shared command storage remains reader/writer constrained; M06 routes add admin-only protection.
CREATE POLICY release_idem_admin_insert ON core_command_idempotency AS RESTRICTIVE FOR INSERT WITH CHECK(route NOT LIKE 'release:%' OR app_has_project_admin(tenant_id,project_id,principal_id));
CREATE POLICY release_idem_admin_read ON core_command_idempotency AS RESTRICTIVE FOR SELECT USING(route NOT LIKE 'release:%' OR app_has_project_admin(tenant_id,project_id,principal_id));
CREATE POLICY release_idem_admin_expire ON core_command_idempotency AS RESTRICTIVE FOR DELETE USING(route NOT LIKE 'release:%' OR app_has_project_admin(tenant_id,project_id,principal_id));
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='test365alm_runtime') THEN
 GRANT SELECT,INSERT ON release_plan,release_cycle,release_scope,quality_snapshot,release_outbox_event,release_number_allocator TO test365alm_runtime;
 GRANT UPDATE(name,description,start_date,end_date,status,row_version,scope_version,updated_at) ON release_plan TO test365alm_runtime;
 GRANT UPDATE(name,start_date,end_date,status,row_version,updated_at) ON release_cycle TO test365alm_runtime;
 GRANT UPDATE(next_number) ON release_number_allocator TO test365alm_runtime;
 GRANT DELETE ON release_scope TO test365alm_runtime;
END IF; END $$;
