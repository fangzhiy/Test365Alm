-- R09 additive defect core. Old migrations and execution history are unchanged.
CREATE TABLE defect_number_allocator (
 tenant_id UUID NOT NULL,project_id UUID NOT NULL,next_number BIGINT NOT NULL DEFAULT 1 CHECK(next_number>0),
 PRIMARY KEY(tenant_id,project_id),FOREIGN KEY(tenant_id,project_id) REFERENCES project(tenant_id,id)
);
CREATE TABLE defect (
 tenant_id UUID NOT NULL,project_id UUID NOT NULL,id UUID PRIMARY KEY,
 display_number BIGINT NOT NULL CHECK(display_number>0),row_version BIGINT NOT NULL CHECK(row_version>0),
 status TEXT NOT NULL CHECK(status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED','REOPENED')),
 title TEXT NOT NULL CHECK(length(btrim(title)) BETWEEN 1 AND 500),
 severity TEXT NOT NULL CHECK(severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
 priority TEXT NOT NULL CHECK(priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
 current_revision_id UUID NOT NULL,resolved_revision_id UUID,
 created_by UUID NOT NULL REFERENCES principal(id),created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,project_id,id),UNIQUE(tenant_id,project_id,display_number),
 FOREIGN KEY(tenant_id,project_id) REFERENCES project(tenant_id,id)
);
CREATE TABLE defect_revision (
 tenant_id UUID NOT NULL,project_id UUID NOT NULL,defect_id UUID NOT NULL,id UUID PRIMARY KEY,
 revision_no BIGINT NOT NULL CHECK(revision_no>0),title TEXT NOT NULL CHECK(length(btrim(title)) BETWEEN 1 AND 500),
 description TEXT NOT NULL CHECK(length(description)<=100000),
 severity TEXT NOT NULL CHECK(severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
 priority TEXT NOT NULL CHECK(priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
 status TEXT NOT NULL CHECK(status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED','REOPENED')),
 reason TEXT NOT NULL DEFAULT '' CHECK(length(reason)<=10000),
 created_by UUID NOT NULL REFERENCES principal(id),created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,project_id,defect_id,id),UNIQUE(tenant_id,project_id,defect_id,revision_no),
 UNIQUE(tenant_id,project_id,defect_id,id,revision_no,title,severity,priority,status),
 FOREIGN KEY(tenant_id,project_id,defect_id) REFERENCES defect(tenant_id,project_id,id)
);
ALTER TABLE defect ADD CONSTRAINT defect_current_snapshot_fk
 FOREIGN KEY(tenant_id,project_id,id,current_revision_id,row_version,title,severity,priority,status)
 REFERENCES defect_revision(tenant_id,project_id,defect_id,id,revision_no,title,severity,priority,status) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE defect ADD CONSTRAINT defect_resolution_fk FOREIGN KEY(tenant_id,project_id,id,resolved_revision_id)
 REFERENCES defect_revision(tenant_id,project_id,defect_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE defect_link (
 tenant_id UUID NOT NULL,project_id UUID NOT NULL,id UUID PRIMARY KEY,defect_id UUID NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('RUN_STEP','REQUIREMENT')),
 requirement_id UUID,requirement_revision_id UUID,test_case_id UUID,test_revision_id UUID,
 run_id UUID,attempt_id UUID,manifest_id UUID,step_key UUID,
 actual_result TEXT,expected_result TEXT,source_conclusion TEXT,source_version BIGINT,
 created_by UUID NOT NULL REFERENCES principal(id),created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(tenant_id,project_id,defect_id) REFERENCES defect(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id,requirement_id,requirement_revision_id) REFERENCES requirement_revision(tenant_id,project_id,requirement_id,id),
 FOREIGN KEY(tenant_id,project_id,test_case_id,test_revision_id) REFERENCES test_revision(tenant_id,project_id,test_case_id,id),
 FOREIGN KEY(tenant_id,project_id,run_id,manifest_id) REFERENCES execution_run(tenant_id,project_id,id,manifest_id),
 FOREIGN KEY(tenant_id,project_id,run_id,attempt_id) REFERENCES run_attempt(tenant_id,project_id,run_id,id),
 FOREIGN KEY(tenant_id,project_id,attempt_id,manifest_id) REFERENCES run_attempt(tenant_id,project_id,id,manifest_id),
 FOREIGN KEY(tenant_id,project_id,manifest_id,step_key) REFERENCES execution_manifest_step(tenant_id,project_id,manifest_id,step_key),
 FOREIGN KEY(attempt_id,step_key) REFERENCES run_step(attempt_id,step_key),
 CHECK((kind='REQUIREMENT' AND requirement_id IS NOT NULL AND requirement_revision_id IS NOT NULL
  AND test_case_id IS NULL AND test_revision_id IS NULL AND run_id IS NULL AND attempt_id IS NULL AND manifest_id IS NULL AND step_key IS NULL
  AND actual_result IS NULL AND expected_result IS NULL AND source_conclusion IS NULL AND source_version IS NULL)
 OR(kind='RUN_STEP' AND requirement_id IS NULL AND requirement_revision_id IS NULL AND test_case_id IS NOT NULL AND test_revision_id IS NOT NULL
  AND run_id IS NOT NULL AND attempt_id IS NOT NULL AND manifest_id IS NOT NULL AND step_key IS NOT NULL
  AND actual_result IS NOT NULL AND expected_result IS NOT NULL AND source_conclusion IS NOT NULL AND source_conclusion IN ('FAIL','BLOCKED') AND source_version IS NOT NULL AND source_version>0))
);
CREATE UNIQUE INDEX defect_one_source ON defect_link(tenant_id,project_id,defect_id) WHERE kind='RUN_STEP';
CREATE UNIQUE INDEX defect_unique_requirement ON defect_link(tenant_id,project_id,defect_id,requirement_id) WHERE kind='REQUIREMENT';
CREATE TABLE defect_retest (
 tenant_id UUID NOT NULL,project_id UUID NOT NULL,id UUID PRIMARY KEY,defect_id UUID NOT NULL,resolved_revision_id UUID NOT NULL,
 run_id UUID NOT NULL,attempt_id UUID NOT NULL,
 created_by UUID NOT NULL REFERENCES principal(id),created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,project_id,defect_id,attempt_id),
 FOREIGN KEY(tenant_id,project_id,defect_id,resolved_revision_id) REFERENCES defect_revision(tenant_id,project_id,defect_id,id),
 FOREIGN KEY(tenant_id,project_id,run_id,attempt_id) REFERENCES run_attempt(tenant_id,project_id,run_id,id)
);
CREATE TABLE defect_outbox_event (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id UUID NOT NULL,project_id UUID NOT NULL,defect_id UUID NOT NULL,
 object_revision UUID NOT NULL,event_type TEXT NOT NULL,payload_version INTEGER NOT NULL DEFAULT 1 CHECK(payload_version=1),
 payload JSONB NOT NULL,occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(tenant_id,project_id,defect_id,object_revision) REFERENCES defect_revision(tenant_id,project_id,defect_id,id)
);
CREATE FUNCTION app_defect_pass(t UUID,p UUID,d UUID,resolution UUID) RETURNS UUID LANGUAGE sql STABLE AS $$
 SELECT a.id FROM defect_retest v JOIN run_attempt a ON a.tenant_id=v.tenant_id AND a.project_id=v.project_id AND a.run_id=v.run_id AND a.id=v.attempt_id
 JOIN defect_link l ON l.tenant_id=v.tenant_id AND l.project_id=v.project_id AND l.defect_id=v.defect_id AND l.kind='RUN_STEP' AND l.run_id=v.run_id
 JOIN run_step s ON s.tenant_id=a.tenant_id AND s.project_id=a.project_id AND s.attempt_id=a.id AND s.step_key=l.step_key
 WHERE v.tenant_id=t AND v.project_id=p AND v.defect_id=d AND v.resolved_revision_id=resolution
 AND a.status='FINISHED' AND a.conclusion='PASS' AND s.conclusion='PASS'
 AND NOT EXISTS(SELECT 1 FROM run_attempt newer WHERE newer.tenant_id=t AND newer.project_id=p AND newer.run_id=a.run_id AND newer.attempt_no>a.attempt_no)
 ORDER BY a.attempt_no DESC,a.id DESC LIMIT 1
$$;
REVOKE ALL ON FUNCTION app_defect_pass(UUID,UUID,UUID,UUID) FROM PUBLIC;

CREATE FUNCTION defect_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'OPEN' OR NEW.row_version<>1 OR NEW.resolved_revision_id IS NOT NULL THEN
   RAISE EXCEPTION 'defect initial state must be OPEN version 1' USING ERRCODE='23514'; END IF;
 ELSE
  IF OLD.status='CLOSED' THEN RAISE EXCEPTION 'closed defect is immutable' USING ERRCODE='55006'; END IF;
  IF NEW.row_version<>OLD.row_version+1 OR NEW.current_revision_id=OLD.current_revision_id THEN
   RAISE EXCEPTION 'defect update requires a new consecutive revision' USING ERRCODE='23514'; END IF;
  IF NEW.status<>OLD.status AND (OLD.status||':'||NEW.status) NOT IN
   ('OPEN:IN_PROGRESS','OPEN:RESOLVED','IN_PROGRESS:RESOLVED','RESOLVED:CLOSED','RESOLVED:REOPENED','REOPENED:IN_PROGRESS','REOPENED:RESOLVED') THEN
   RAISE EXCEPTION 'invalid defect transition' USING ERRCODE='23514'; END IF;
  IF NEW.status='RESOLVED' AND OLD.status<>'RESOLVED' THEN
   IF NEW.resolved_revision_id IS DISTINCT FROM NEW.current_revision_id OR NOT EXISTS(SELECT 1 FROM defect_revision r WHERE r.id=NEW.current_revision_id AND length(btrim(r.reason))>0) THEN
    RAISE EXCEPTION 'resolution explanation and revision required' USING ERRCODE='23514'; END IF;
  ELSIF NEW.resolved_revision_id IS DISTINCT FROM OLD.resolved_revision_id THEN
   RAISE EXCEPTION 'resolution evidence cannot be replaced outside resolving' USING ERRCODE='23514';
  END IF;
  IF NEW.status='CLOSED' AND EXISTS(SELECT 1 FROM defect_link l WHERE l.defect_id=NEW.id AND l.kind='RUN_STEP')
   AND app_defect_pass(NEW.tenant_id,NEW.project_id,NEW.id,NEW.resolved_revision_id) IS NULL THEN
   RAISE EXCEPTION 'related successful retest required' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER defect_state_guard BEFORE INSERT OR UPDATE ON defect FOR EACH ROW EXECUTE FUNCTION defect_guard();

CREATE FUNCTION defect_link_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM defect d WHERE d.tenant_id=NEW.tenant_id AND d.project_id=NEW.project_id AND d.id=NEW.defect_id AND d.row_version=1 AND d.created_at=CURRENT_TIMESTAMP) THEN
  RAISE EXCEPTION 'defect links can only be assembled in creation transaction' USING ERRCODE='55006'; END IF;
 IF NEW.kind='RUN_STEP' AND NOT EXISTS(
  SELECT 1 FROM run_step s JOIN run_attempt a ON a.tenant_id=s.tenant_id AND a.project_id=s.project_id AND a.id=s.attempt_id
  JOIN execution_manifest m ON m.tenant_id=a.tenant_id AND m.project_id=a.project_id AND m.id=a.manifest_id
  WHERE s.tenant_id=NEW.tenant_id AND s.project_id=NEW.project_id AND a.run_id=NEW.run_id AND a.id=NEW.attempt_id AND s.step_key=NEW.step_key
   AND a.status='FINISHED' AND m.source_test_case_id=NEW.test_case_id AND m.source_revision_id=NEW.test_revision_id
   AND s.actual_result=NEW.actual_result AND s.expected=NEW.expected_result AND s.conclusion=NEW.source_conclusion AND s.row_version=NEW.source_version) THEN
  RAISE EXCEPTION 'defect source must match finished failing step snapshot' USING ERRCODE='23514'; END IF;
 IF NEW.kind='REQUIREMENT' AND NOT EXISTS(SELECT 1 FROM requirement r WHERE r.tenant_id=NEW.tenant_id AND r.project_id=NEW.project_id AND r.id=NEW.requirement_id AND r.deleted_at IS NULL AND r.current_revision_id=NEW.requirement_revision_id) THEN
  RAISE EXCEPTION 'defect requirement must be current valid endpoint' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER defect_link_snapshot_guard BEFORE INSERT ON defect_link FOR EACH ROW EXECUTE FUNCTION defect_link_guard();
CREATE FUNCTION defect_retest_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM defect d JOIN defect_revision resolution ON resolution.id=d.resolved_revision_id
  JOIN defect_link l ON l.defect_id=d.id AND l.kind='RUN_STEP'
  JOIN run_attempt original ON original.id=l.attempt_id AND original.run_id=l.run_id
  JOIN run_attempt fresh ON fresh.run_id=l.run_id AND fresh.id=NEW.attempt_id
  WHERE d.tenant_id=NEW.tenant_id AND d.project_id=NEW.project_id AND d.id=NEW.defect_id AND d.status='RESOLVED'
   AND d.resolved_revision_id=NEW.resolved_revision_id AND l.run_id=NEW.run_id AND fresh.attempt_no>original.attempt_no
   AND fresh.started_at>=resolution.created_at AND fresh.status='RUNNING' AND fresh.row_version=1) THEN
  RAISE EXCEPTION 'defect retest must be new related attempt in current resolution cycle' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER defect_retest_scope_guard BEFORE INSERT ON defect_retest FOR EACH ROW EXECUTE FUNCTION defect_retest_guard();

DO $$ DECLARE tbl TEXT; BEGIN
 FOREACH tbl IN ARRAY ARRAY['defect','defect_revision','defect_link','defect_retest','defect_outbox_event','defect_number_allocator'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY defect_scoped_read ON %I FOR SELECT USING(app_core_scope(tenant_id,project_id))',tbl);
  EXECUTE format('CREATE POLICY defect_scoped_insert ON %I FOR INSERT WITH CHECK(app_core_scope(tenant_id,project_id) AND app_has_active_project_writer(tenant_id,project_id,NULLIF(current_setting(''test365alm.principal_id'',true),'''')::UUID))',tbl);
 END LOOP;
END $$;
DO $$ DECLARE tbl TEXT; BEGIN
 FOREACH tbl IN ARRAY ARRAY['defect','defect_revision','defect_link','defect_retest'] LOOP
  EXECUTE format('CREATE POLICY defect_actor_insert ON %I AS RESTRICTIVE FOR INSERT WITH CHECK(created_by=NULLIF(current_setting(''test365alm.principal_id'',true),'''')::UUID)',tbl);
 END LOOP;
END $$;
CREATE POLICY defect_write ON defect FOR UPDATE USING(app_core_scope(tenant_id,project_id) AND app_has_active_project_writer(tenant_id,project_id,NULLIF(current_setting('test365alm.principal_id',true),'')::UUID))
 WITH CHECK(app_core_scope(tenant_id,project_id) AND app_has_active_project_writer(tenant_id,project_id,NULLIF(current_setting('test365alm.principal_id',true),'')::UUID));
CREATE POLICY defect_allocator_write ON defect_number_allocator FOR UPDATE USING(app_core_scope(tenant_id,project_id) AND app_has_active_project_writer(tenant_id,project_id,NULLIF(current_setting('test365alm.principal_id',true),'')::UUID));
CREATE POLICY defect_audit_insert ON audit_event FOR INSERT WITH CHECK(app_core_scope(tenant_id,project_id)
 AND actor_principal_id=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID AND app_has_active_project_writer(tenant_id,project_id,actor_principal_id)
 AND object_type='defect' AND action IN ('defect.created','defect.revised','defect.transitioned','defect.retest-started')
 AND EXISTS(SELECT 1 FROM defect d WHERE d.tenant_id=audit_event.tenant_id AND d.project_id=audit_event.project_id AND d.id=audit_event.object_id));
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='test365alm_runtime') THEN
 GRANT SELECT,INSERT ON defect,defect_revision,defect_link,defect_retest,defect_outbox_event,defect_number_allocator TO test365alm_runtime;
 GRANT UPDATE(row_version,title,severity,priority,status,current_revision_id,resolved_revision_id) ON defect TO test365alm_runtime;
 GRANT UPDATE(next_number) ON defect_number_allocator TO test365alm_runtime;
 GRANT EXECUTE ON FUNCTION app_defect_pass(UUID,UUID,UUID,UUID) TO test365alm_runtime;
END IF; END $$;
