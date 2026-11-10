-- V23 is reserved for the independent business-field slice. This branch adds
-- ONE migration to the V22 baseline, irrespective of the maximum version.
ALTER TABLE test_instance
 ADD COLUMN assignee_id UUID REFERENCES principal(id),
 ADD COLUMN assignee_name TEXT,
 ADD COLUMN assignment_version BIGINT NOT NULL DEFAULT 1 CHECK(assignment_version>0),
 ADD CONSTRAINT instance_assignee_pair CHECK((assignee_id IS NULL)=(assignee_name IS NULL));

-- Bounded identity lookup: no runtime SELECT policy on unrelated memberships
-- is opened. The locking path is only for a specific target and a writer.
CREATE FUNCTION app_execution_people(t UUID,p UUID,target UUID,after_id UUID,n INTEGER,lock_target BOOLEAN)
RETURNS TABLE(id UUID,display_name TEXT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
BEGIN
 IF NOT public.app_core_scope(t,p) THEN RETURN; END IF;
 IF n IS NULL OR n<1 OR n>201 THEN RAISE EXCEPTION 'invalid identity page size' USING ERRCODE='22023'; END IF;
 IF lock_target THEN
  IF target IS NULL OR NOT public.app_has_active_project_writer(t,p,NULLIF(current_setting('test365alm.principal_id',true),'')::UUID)
   THEN RAISE EXCEPTION 'specific target and writer required' USING ERRCODE='42501'; END IF;
  RETURN QUERY SELECT pr.id,pr.display_name FROM public.principal pr
   JOIN public.project_member pm ON pm.principal_id=pr.id AND pm.tenant_id=t AND pm.project_id=p
   JOIN public.tenant_member tm ON tm.principal_id=pr.id AND tm.tenant_id=t
   WHERE pr.id=target AND public.app_has_active_project_writer(t,p,pr.id)
   ORDER BY pr.id LIMIT 1 FOR SHARE OF pr,pm,tm;
 ELSE
  RETURN QUERY SELECT pr.id,pr.display_name FROM public.principal pr
   JOIN public.project_member pm ON pm.principal_id=pr.id AND pm.tenant_id=t AND pm.project_id=p
   WHERE (target IS NULL OR pr.id=target) AND (after_id IS NULL OR pr.id>after_id)
    AND public.app_has_active_project_writer(t,p,pr.id)
   ORDER BY pr.id LIMIT n;
 END IF;
END $$;
REVOKE ALL ON FUNCTION app_execution_people(UUID,UUID,UUID,UUID,INTEGER,BOOLEAN) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_execution_people(UUID,UUID,UUID,UUID,INTEGER,BOOLEAN) TO test365alm_runtime;

CREATE FUNCTION execution_assignment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actual_name TEXT;
BEGIN
 IF TG_OP='UPDATE' AND (
   (to_jsonb(NEW)-ARRAY['assignee_id','assignee_name','assignment_version']) IS DISTINCT FROM
   (to_jsonb(OLD)-ARRAY['assignee_id','assignee_name','assignment_version'])
   OR NEW.assignment_version<>OLD.assignment_version+1) THEN
  RAISE EXCEPTION 'instance identity or assignment version is immutable' USING ERRCODE='55006';
 END IF;
 IF TG_OP='INSERT' AND (NEW.assignment_version<>1 OR NEW.assignee_id IS NOT NULL OR NEW.assignee_name IS NOT NULL) THEN
  RAISE EXCEPTION 'new instance must start unassigned at version 1' USING ERRCODE='23514';
 END IF;
 IF NEW.assignee_id IS NOT NULL THEN
  SELECT x.display_name INTO actual_name FROM app_execution_people(NEW.tenant_id,NEW.project_id,NEW.assignee_id,NULL,1,true) x;
  IF actual_name IS NULL OR NEW.assignee_name IS DISTINCT FROM actual_name THEN
   RAISE EXCEPTION 'assignment requires an active project writer and server identity' USING ERRCODE='23514';
  END IF;
 ELSIF NEW.assignee_name IS NOT NULL THEN
  RAISE EXCEPTION 'unassigned instance has no name' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER execution_assignment_rules BEFORE INSERT OR UPDATE ON test_instance
 FOR EACH ROW EXECUTE FUNCTION execution_assignment_guard();
GRANT UPDATE(assignee_id,assignee_name,assignment_version) ON test_instance TO test365alm_runtime;
CREATE INDEX execution_grid_assignment ON test_instance(tenant_id,project_id,test_set_id,assignee_id,display_order,id);
CREATE INDEX execution_grid_instance_runs ON execution_run(tenant_id,project_id,test_instance_id,id);

CREATE POLICY execution_assignment_audit ON audit_event FOR INSERT WITH CHECK (
 app_core_scope(tenant_id,project_id)
 AND app_has_active_project_writer(tenant_id,project_id,NULLIF(current_setting('test365alm.principal_id',true),'')::UUID)
 AND actor_principal_id=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID
 AND object_type='test_instance' AND action='test.instance.assigned'
 AND EXISTS(SELECT 1 FROM test_instance i WHERE i.tenant_id=audit_event.tenant_id
  AND i.project_id=audit_event.project_id AND i.id=audit_event.object_id)
);

ALTER TABLE execution_manifest ADD COLUMN assignee_id UUID REFERENCES principal(id), ADD COLUMN assignee_name TEXT,
 ADD CONSTRAINT manifest_assignee_pair CHECK((assignee_id IS NULL)=(assignee_name IS NULL));
-- Keep the established build-only transition, checking every field including
-- new snapshot columns. Never update old manifests or recompute old hashes.
CREATE OR REPLACE FUNCTION execution_manifest_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF COALESCE(current_setting('test365alm.execution_building',true),'false')<>'true' OR NEW.build_complete IS DISTINCT FROM FALSE THEN
   RAISE EXCEPTION 'execution manifest must start as an incomplete build' USING ERRCODE='55006';
  END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='UPDATE' AND OLD.build_complete=FALSE AND NEW.build_complete=TRUE
  AND COALESCE(current_setting('test365alm.execution_building',true),'false')='true'
  AND (to_jsonb(NEW)-'build_complete')=(to_jsonb(OLD)-'build_complete') THEN RETURN NEW; END IF;
 RAISE EXCEPTION 'execution manifest is immutable' USING ERRCODE='55006';
END $$;

CREATE FUNCTION execution_manifest_assignment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE assigned UUID; actual_name TEXT;
BEGIN
 SELECT i.assignee_id INTO assigned FROM test_instance i
  WHERE i.tenant_id=NEW.tenant_id AND i.project_id=NEW.project_id AND i.id=NEW.test_instance_id FOR SHARE OF i;
 IF NEW.assignee_id IS DISTINCT FROM assigned THEN
  RAISE EXCEPTION 'manifest assignment differs from its instance' USING ERRCODE='23514';
 END IF;
 IF assigned IS NOT NULL THEN
  SELECT x.display_name INTO actual_name FROM app_execution_people(NEW.tenant_id,NEW.project_id,assigned,NULL,1,true) x;
  IF actual_name IS NULL OR NEW.assignee_name IS DISTINCT FROM actual_name THEN
   RAISE EXCEPTION 'manifest requires current valid assignment identity' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER execution_manifest_assignment_rules BEFORE INSERT ON execution_manifest
 FOR EACH ROW EXECUTE FUNCTION execution_manifest_assignment_guard();

ALTER TABLE run_step ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid(),
 ADD COLUMN executed_at TIMESTAMPTZ,
 ADD CONSTRAINT run_step_scoped_id UNIQUE(tenant_id,project_id,id);
CREATE FUNCTION execution_step_clock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.executed_at IS NOT NULL THEN RAISE EXCEPTION 'new step has no execution time' USING ERRCODE='23514'; END IF;
 ELSE
  NEW.executed_at:=OLD.executed_at;
  IF NEW.executed_at IS NULL AND NEW.conclusion<>'NOT_RUN' THEN NEW.executed_at:=CURRENT_TIMESTAMP; END IF;
  NEW.updated_at:=CURRENT_TIMESTAMP;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER execution_step_clock_rules BEFORE INSERT OR UPDATE ON run_step
 FOR EACH ROW EXECUTE FUNCTION execution_step_clock();

ALTER TABLE attachment_link ADD COLUMN run_step_id UUID,
 ADD CONSTRAINT attachment_run_step_fk FOREIGN KEY(tenant_id,project_id,run_step_id) REFERENCES run_step(tenant_id,project_id,id);
ALTER TABLE attachment_link DROP CONSTRAINT attachment_link_kind_check, DROP CONSTRAINT attachment_link_check;
-- PostgreSQL truncates the old auto-generated unique name. Locate that exact
-- seven-column uniqueness definition rather than guessing a truncated name.
DO $$ DECLARE c RECORD; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='attachment_link'::regclass AND contype='u' AND cardinality(conkey)=7 LOOP
  EXECUTE format('ALTER TABLE attachment_link DROP CONSTRAINT %I',c.conname);
 END LOOP;
END $$;
ALTER TABLE attachment_link
 ADD CONSTRAINT attachment_kind CHECK(kind IN('REQUIREMENT','TEST_CASE','DEFECT','RUN_STEP')),
 ADD CONSTRAINT attachment_target CHECK(
  (kind='REQUIREMENT' AND requirement_id IS NOT NULL AND test_case_id IS NULL AND defect_id IS NULL AND run_step_id IS NULL)
  OR(kind='TEST_CASE' AND test_case_id IS NOT NULL AND requirement_id IS NULL AND defect_id IS NULL AND run_step_id IS NULL)
  OR(kind='DEFECT' AND defect_id IS NOT NULL AND requirement_id IS NULL AND test_case_id IS NULL AND run_step_id IS NULL)
  OR(kind='RUN_STEP' AND run_step_id IS NOT NULL AND requirement_id IS NULL AND test_case_id IS NULL AND defect_id IS NULL)),
 ADD CONSTRAINT attachment_target_revision UNIQUE NULLS NOT DISTINCT
  (tenant_id,project_id,kind,requirement_id,test_case_id,defect_id,run_step_id,asset_revision_id);
CREATE INDEX attachment_run_step_page ON attachment_link(tenant_id,project_id,run_step_id,id);
CREATE FUNCTION execution_step_attachment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE attempt_status TEXT;
BEGIN
 IF NEW.kind='RUN_STEP' THEN
  SELECT a.status INTO attempt_status FROM run_step s JOIN run_attempt a
   ON a.tenant_id=s.tenant_id AND a.project_id=s.project_id AND a.id=s.attempt_id
   WHERE s.tenant_id=NEW.tenant_id AND s.project_id=NEW.project_id AND s.id=NEW.run_step_id FOR UPDATE OF a;
  IF attempt_status IS DISTINCT FROM 'RUNNING' THEN
   RAISE EXCEPTION 'step evidence requires a running scoped attempt' USING ERRCODE='55006';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER execution_step_attachment_rules BEFORE INSERT ON attachment_link
 FOR EACH ROW EXECUTE FUNCTION execution_step_attachment_guard();
