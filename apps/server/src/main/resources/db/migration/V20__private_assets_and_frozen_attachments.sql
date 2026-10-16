-- R11 additive M05 slice: external storage is not part of PostgreSQL's transaction.
-- Candidate keys are journaled at session creation, before any external I/O.
-- Scanner attestation is a separate restricted DB identity, never runtime or the migration owner.
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='test365alm_scanner') THEN CREATE ROLE test365alm_scanner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS; END IF;
END $$;
CREATE TABLE upload_session (
 tenant_id UUID NOT NULL,project_id UUID NOT NULL,id UUID PRIMARY KEY,
 existing_asset_id UUID,candidate_asset_id UUID NOT NULL,candidate_revision_id UUID NOT NULL UNIQUE,
 quarantine_key TEXT NOT NULL UNIQUE,published_key TEXT NOT NULL UNIQUE,
 filename TEXT NOT NULL CHECK(length(filename) BETWEEN 1 AND 200),
 declared_size BIGINT NOT NULL CHECK(declared_size BETWEEN 1 AND 26214400),
 declared_sha256 TEXT NOT NULL CHECK(declared_sha256~'^[0-9a-f]{64}$'),
 declared_content_type TEXT NOT NULL CHECK(declared_content_type IN('text/plain','application/pdf','image/png','image/jpeg')),
 actual_size BIGINT,computed_sha256 TEXT,detected_mime TEXT,
 state TEXT NOT NULL DEFAULT 'CREATED' CHECK(state IN('CREATED','UPLOADED','SCAN_FAILED','PUBLISHED','REJECTED')),
 cleanup_pending BOOLEAN NOT NULL DEFAULT false,last_error_code TEXT,
 row_version BIGINT NOT NULL DEFAULT 1 CHECK(row_version>0),created_by UUID NOT NULL REFERENCES principal(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,project_id,id),FOREIGN KEY(tenant_id,project_id) REFERENCES project(tenant_id,id),
 CHECK((state='CREATED' AND actual_size IS NULL AND computed_sha256 IS NULL AND detected_mime IS NULL)
 OR(state<>'CREATED' AND actual_size IS NOT NULL AND computed_sha256 IS NOT NULL AND detected_mime IS NOT NULL AND actual_size=declared_size AND computed_sha256=declared_sha256 AND detected_mime=declared_content_type)),
 CHECK(quarantine_key='quarantine/'||tenant_id::text||'/'||project_id::text||'/'||id::text),
 CHECK(published_key='published/'||tenant_id::text||'/'||project_id::text||'/'||candidate_revision_id::text),
 CHECK(existing_asset_id IS NULL OR existing_asset_id=candidate_asset_id),CHECK(NOT cleanup_pending OR state='PUBLISHED')
);
CREATE TABLE asset (
 tenant_id UUID NOT NULL,project_id UUID NOT NULL,id UUID PRIMARY KEY,current_revision_id UUID NOT NULL,
 next_revision_no BIGINT NOT NULL DEFAULT 1 CHECK(next_revision_no>0),created_by UUID NOT NULL REFERENCES principal(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id) REFERENCES project(tenant_id,id)
);
ALTER TABLE upload_session ADD CONSTRAINT upload_existing_asset_fk FOREIGN KEY(tenant_id,project_id,existing_asset_id) REFERENCES asset(tenant_id,project_id,id);
CREATE TABLE scan_result (
 tenant_id UUID NOT NULL,project_id UUID NOT NULL,id UUID PRIMARY KEY,upload_id UUID NOT NULL,
 status TEXT NOT NULL CHECK(status IN('CLEAN','INFECTED','ERROR')),engine TEXT NOT NULL CHECK(length(engine) BETWEEN 1 AND 100),
 detail_code TEXT NOT NULL CHECK(length(detail_code) BETWEEN 1 AND 100),sha256 TEXT NOT NULL CHECK(sha256~'^[0-9a-f]{64}$'),
 created_by UUID NOT NULL REFERENCES principal(id),created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,project_id,upload_id,id),FOREIGN KEY(tenant_id,project_id,upload_id) REFERENCES upload_session(tenant_id,project_id,id)
);
CREATE UNIQUE INDEX scan_one_clean ON scan_result(tenant_id,project_id,upload_id) WHERE status='CLEAN';
CREATE FUNCTION asset_scan_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM upload_session u WHERE u.tenant_id=NEW.tenant_id AND u.project_id=NEW.project_id AND u.id=NEW.upload_id
  AND u.created_by=NEW.created_by AND u.computed_sha256=NEW.sha256 AND u.state IN('UPLOADED','SCAN_FAILED')) THEN
  RAISE EXCEPTION 'scan attestation requires owned confirmed upload bytes' USING ERRCODE='23514'; END IF;
 IF NEW.status='CLEAN' AND EXISTS(SELECT 1 FROM scan_result s WHERE s.tenant_id=NEW.tenant_id AND s.project_id=NEW.project_id AND s.upload_id=NEW.upload_id AND s.status='INFECTED') THEN
  RAISE EXCEPTION 'infected scan attestation cannot be reversed' USING ERRCODE='55006'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER asset_scan_rules BEFORE INSERT ON scan_result FOR EACH ROW EXECUTE FUNCTION asset_scan_guard();
CREATE TABLE asset_revision (
 tenant_id UUID NOT NULL,project_id UUID NOT NULL,asset_id UUID NOT NULL,id UUID PRIMARY KEY,
 revision_no BIGINT NOT NULL CHECK(revision_no>0),upload_id UUID NOT NULL,scan_result_id UUID NOT NULL,
 object_key TEXT NOT NULL UNIQUE,size BIGINT NOT NULL CHECK(size BETWEEN 1 AND 26214400),sha256 TEXT NOT NULL CHECK(sha256~'^[0-9a-f]{64}$'),
 mime TEXT NOT NULL CHECK(mime IN('text/plain','application/pdf','image/png','image/jpeg')),filename TEXT NOT NULL CHECK(length(filename) BETWEEN 1 AND 200),
 created_by UUID NOT NULL REFERENCES principal(id),created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,project_id,asset_id,id),UNIQUE(tenant_id,project_id,asset_id,revision_no),UNIQUE(tenant_id,project_id,upload_id),
 FOREIGN KEY(tenant_id,project_id,asset_id) REFERENCES asset(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id,upload_id,scan_result_id) REFERENCES scan_result(tenant_id,project_id,upload_id,id)
);
ALTER TABLE asset ADD CONSTRAINT asset_current_revision_fk FOREIGN KEY(tenant_id,project_id,id,current_revision_id) REFERENCES asset_revision(tenant_id,project_id,asset_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE attachment_link (
 tenant_id UUID NOT NULL,project_id UUID NOT NULL,id UUID PRIMARY KEY,kind TEXT NOT NULL CHECK(kind IN('REQUIREMENT','TEST_CASE','DEFECT')),
 requirement_id UUID,test_case_id UUID,defect_id UUID,asset_id UUID NOT NULL,asset_revision_id UUID NOT NULL,
 created_by UUID NOT NULL REFERENCES principal(id),created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id,requirement_id) REFERENCES requirement(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id,test_case_id) REFERENCES test_case(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id,defect_id) REFERENCES defect(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id,asset_id,asset_revision_id) REFERENCES asset_revision(tenant_id,project_id,asset_id,id),
 CHECK((kind='REQUIREMENT' AND requirement_id IS NOT NULL AND test_case_id IS NULL AND defect_id IS NULL)
 OR(kind='TEST_CASE' AND test_case_id IS NOT NULL AND requirement_id IS NULL AND defect_id IS NULL)
 OR(kind='DEFECT' AND defect_id IS NOT NULL AND requirement_id IS NULL AND test_case_id IS NULL)),
 UNIQUE NULLS NOT DISTINCT(tenant_id,project_id,kind,requirement_id,test_case_id,defect_id,asset_revision_id)
);
CREATE TABLE asset_outbox_event (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id UUID NOT NULL,project_id UUID NOT NULL,
 upload_id UUID,asset_id UUID,revision_id UUID,link_id UUID,
 event_type TEXT NOT NULL CHECK(event_type IN('UPLOAD_SESSION_CREATED','UPLOAD_CONTENT_CONFIRMED','UPLOAD_SCAN_CLEAN','UPLOAD_SCAN_REJECTED','ASSET_CREATED','ASSET_REVISION_PUBLISHED','ATTACHMENT_LINK_CREATED')),
 payload_version INTEGER NOT NULL DEFAULT 1 CHECK(payload_version=1),payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='object'),
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(upload_id IS NOT NULL OR link_id IS NOT NULL),CHECK(revision_id IS NULL OR asset_id IS NOT NULL),
 FOREIGN KEY(tenant_id,project_id,upload_id) REFERENCES upload_session(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id,asset_id) REFERENCES asset(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id,asset_id,revision_id) REFERENCES asset_revision(tenant_id,project_id,asset_id,id),
 FOREIGN KEY(tenant_id,project_id,link_id) REFERENCES attachment_link(tenant_id,project_id,id)
);
CREATE INDEX upload_owner_page ON upload_session(tenant_id,project_id,created_by,id);
CREATE INDEX asset_page ON asset(tenant_id,project_id,id);
CREATE INDEX asset_revision_page ON asset_revision(tenant_id,project_id,asset_id,id);
CREATE INDEX attachment_requirement_page ON attachment_link(tenant_id,project_id,requirement_id,id);
CREATE INDEX attachment_test_page ON attachment_link(tenant_id,project_id,test_case_id,id);
CREATE INDEX attachment_defect_page ON attachment_link(tenant_id,project_id,defect_id,id);

CREATE FUNCTION upload_session_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'CREATED' OR NEW.row_version<>1 THEN RAISE EXCEPTION 'upload initial state must be CREATED version 1' USING ERRCODE='23514'; END IF;
 ELSE
  IF ROW(NEW.tenant_id,NEW.project_id,NEW.id,NEW.existing_asset_id,NEW.candidate_asset_id,NEW.candidate_revision_id,NEW.quarantine_key,NEW.published_key,NEW.filename,NEW.declared_size,NEW.declared_sha256,NEW.declared_content_type,NEW.created_by,NEW.created_at)
   IS DISTINCT FROM ROW(OLD.tenant_id,OLD.project_id,OLD.id,OLD.existing_asset_id,OLD.candidate_asset_id,OLD.candidate_revision_id,OLD.quarantine_key,OLD.published_key,OLD.filename,OLD.declared_size,OLD.declared_sha256,OLD.declared_content_type,OLD.created_by,OLD.created_at) THEN
   RAISE EXCEPTION 'upload identity and declaration are immutable' USING ERRCODE='55006'; END IF;
  IF NEW.row_version<>OLD.row_version+1 THEN RAISE EXCEPTION 'upload version must advance once' USING ERRCODE='23514'; END IF;
  IF OLD.state<>'CREATED' AND ROW(NEW.actual_size,NEW.computed_sha256,NEW.detected_mime) IS DISTINCT FROM ROW(OLD.actual_size,OLD.computed_sha256,OLD.detected_mime) THEN
   RAISE EXCEPTION 'confirmed upload receipt is immutable' USING ERRCODE='55006'; END IF;
  IF OLD.state='REJECTED' OR(OLD.state='PUBLISHED' AND (NEW.state<>'PUBLISHED' OR NEW.cleanup_pending OR NOT OLD.cleanup_pending)) THEN
   RAISE EXCEPTION 'terminal upload cannot be reopened' USING ERRCODE='55006'; END IF;
  IF NEW.state<>OLD.state AND (OLD.state||':'||NEW.state) NOT IN('CREATED:UPLOADED','UPLOADED:SCAN_FAILED','UPLOADED:REJECTED','UPLOADED:PUBLISHED','SCAN_FAILED:REJECTED','SCAN_FAILED:PUBLISHED') THEN
   RAISE EXCEPTION 'invalid upload transition' USING ERRCODE='23514'; END IF;
  IF NEW.state='PUBLISHED' AND NOT EXISTS(SELECT 1 FROM asset_revision r WHERE r.tenant_id=NEW.tenant_id AND r.project_id=NEW.project_id AND r.id=NEW.candidate_revision_id AND r.upload_id=NEW.id) THEN
   RAISE EXCEPTION 'published upload requires confirmed revision' USING ERRCODE='23514'; END IF;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER upload_session_rules BEFORE INSERT OR UPDATE ON upload_session FOR EACH ROW EXECUTE FUNCTION upload_session_guard();
CREATE FUNCTION asset_revision_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM upload_session u JOIN scan_result s ON s.tenant_id=u.tenant_id AND s.project_id=u.project_id AND s.upload_id=u.id
  JOIN asset a ON a.tenant_id=u.tenant_id AND a.project_id=u.project_id AND a.id=u.candidate_asset_id
  WHERE u.tenant_id=NEW.tenant_id AND u.project_id=NEW.project_id AND u.id=NEW.upload_id AND u.state IN('UPLOADED','SCAN_FAILED')
   AND u.candidate_revision_id=NEW.id AND u.candidate_asset_id=NEW.asset_id AND u.published_key=NEW.object_key
   AND u.actual_size=NEW.size AND u.computed_sha256=NEW.sha256 AND u.detected_mime=NEW.mime AND u.filename=NEW.filename
   AND u.created_by=NEW.created_by AND s.id=NEW.scan_result_id AND s.status='CLEAN' AND s.sha256=NEW.sha256 AND a.next_revision_no=NEW.revision_no+1
   AND NOT EXISTS(SELECT 1 FROM scan_result bad WHERE bad.tenant_id=u.tenant_id AND bad.project_id=u.project_id AND bad.upload_id=u.id AND bad.status='INFECTED')) THEN
  RAISE EXCEPTION 'asset revision requires owned confirmed CLEAN upload and allocated version' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER asset_revision_rules BEFORE INSERT ON asset_revision FOR EACH ROW EXECUTE FUNCTION asset_revision_guard();
CREATE FUNCTION asset_current_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM asset_revision r WHERE r.tenant_id=NEW.tenant_id AND r.project_id=NEW.project_id AND r.asset_id=NEW.id
  AND r.id=NEW.current_revision_id AND r.revision_no=NEW.next_revision_no-1) THEN
  RAISE EXCEPTION 'asset pointer must advance to allocated published revision' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER asset_current_rules BEFORE UPDATE OF current_revision_id ON asset FOR EACH ROW EXECUTE FUNCTION asset_current_guard();
CREATE FUNCTION attachment_link_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.kind='REQUIREMENT' AND NOT EXISTS(SELECT 1 FROM requirement r WHERE r.tenant_id=NEW.tenant_id AND r.project_id=NEW.project_id AND r.id=NEW.requirement_id AND r.deleted_at IS NULL) THEN
  RAISE EXCEPTION 'attachment requires an active scoped requirement' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM asset_revision r JOIN scan_result s ON s.id=r.scan_result_id WHERE r.tenant_id=NEW.tenant_id AND r.project_id=NEW.project_id AND r.asset_id=NEW.asset_id AND r.id=NEW.asset_revision_id AND s.status='CLEAN') THEN
  RAISE EXCEPTION 'attachment requires a published CLEAN revision' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER attachment_link_rules BEFORE INSERT ON attachment_link FOR EACH ROW EXECUTE FUNCTION attachment_link_guard();

DO $$ DECLARE tbl TEXT; BEGIN
 FOREACH tbl IN ARRAY ARRAY['upload_session','asset','asset_revision','scan_result','attachment_link','asset_outbox_event'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY asset_read ON %I FOR SELECT USING(app_core_scope(tenant_id,project_id))',tbl);
  EXECUTE format('CREATE POLICY asset_insert ON %I FOR INSERT WITH CHECK(app_core_scope(tenant_id,project_id) AND app_has_active_project_writer(tenant_id,project_id,NULLIF(current_setting(''test365alm.principal_id'',true),'''')::UUID))',tbl);
 END LOOP;
 FOREACH tbl IN ARRAY ARRAY['upload_session','asset','asset_revision','scan_result','attachment_link'] LOOP
  EXECUTE format('CREATE POLICY asset_actor ON %I AS RESTRICTIVE FOR INSERT WITH CHECK(created_by=NULLIF(current_setting(''test365alm.principal_id'',true),'''')::UUID)',tbl);
 END LOOP;
END $$;
CREATE POLICY upload_owner_read ON upload_session AS RESTRICTIVE FOR SELECT USING(created_by=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID);
CREATE POLICY scan_trusted_writer ON scan_result AS RESTRICTIVE FOR INSERT WITH CHECK(pg_has_role(current_user,'test365alm_scanner','MEMBER'));
CREATE POLICY upload_owner_write ON upload_session FOR UPDATE USING(app_core_scope(tenant_id,project_id) AND created_by=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID AND app_has_active_project_writer(tenant_id,project_id,created_by))
 WITH CHECK(app_core_scope(tenant_id,project_id) AND created_by=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID AND app_has_active_project_writer(tenant_id,project_id,created_by));
CREATE POLICY asset_current_write ON asset FOR UPDATE USING(app_core_scope(tenant_id,project_id) AND app_has_active_project_writer(tenant_id,project_id,NULLIF(current_setting('test365alm.principal_id',true),'')::UUID));
CREATE POLICY asset_audit_insert ON audit_event FOR INSERT WITH CHECK(app_core_scope(tenant_id,project_id)
 AND actor_principal_id=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID AND app_has_active_project_writer(tenant_id,project_id,actor_principal_id)
 AND ((object_type='upload_session' AND action IN('UPLOAD_SESSION_CREATED','UPLOAD_CONTENT_CONFIRMED','UPLOAD_SCAN_CLEAN','UPLOAD_SCAN_REJECTED','ASSET_CREATED','ASSET_REVISION_PUBLISHED')
 AND EXISTS(SELECT 1 FROM upload_session u WHERE u.tenant_id=audit_event.tenant_id AND u.project_id=audit_event.project_id AND u.id=audit_event.object_id AND u.created_by=actor_principal_id))
 OR(object_type='attachment_link' AND action='ATTACHMENT_LINK_CREATED'
 AND EXISTS(SELECT 1 FROM attachment_link l WHERE l.tenant_id=audit_event.tenant_id AND l.project_id=audit_event.project_id AND l.id=audit_event.object_id))));
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='test365alm_runtime') THEN
 GRANT SELECT,INSERT ON upload_session,asset,asset_revision,attachment_link,asset_outbox_event TO test365alm_runtime;
 GRANT SELECT ON scan_result TO test365alm_runtime;
 GRANT UPDATE(actual_size,computed_sha256,detected_mime,state,cleanup_pending,last_error_code,row_version,updated_at) ON upload_session TO test365alm_runtime;
 GRANT UPDATE(current_revision_id,next_revision_no) ON asset TO test365alm_runtime;
END IF; END $$;
GRANT USAGE ON SCHEMA public TO test365alm_scanner;
GRANT SELECT ON upload_session,scan_result TO test365alm_scanner;
GRANT INSERT ON scan_result TO test365alm_scanner;
GRANT EXECUTE ON FUNCTION app_core_scope(UUID,UUID),app_has_active_project_member(UUID,UUID,UUID),app_has_active_project_writer(UUID,UUID,UUID) TO test365alm_scanner;
