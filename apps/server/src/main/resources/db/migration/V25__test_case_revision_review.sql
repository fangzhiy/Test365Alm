-- V24 belongs to the execution workstream. Review never updates sealed content.
CREATE TABLE test_review_event (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 project_id UUID NOT NULL,
 test_case_id UUID NOT NULL,
 revision_id UUID NOT NULL,
 revision_no BIGINT NOT NULL CHECK(revision_no>0),
 row_version BIGINT NOT NULL CHECK(row_version>1),
 action TEXT NOT NULL CHECK(action IN ('SUBMIT','APPROVE','REJECT')),
 state TEXT NOT NULL CHECK(state IN ('SUBMITTED','APPROVED','REJECTED')),
 reviewer_id UUID NOT NULL REFERENCES principal(id),
 actor_id UUID NOT NULL REFERENCES principal(id),
 content_digest TEXT NOT NULL CHECK(content_digest ~ '^[a-f0-9]{64}$'),
 reason TEXT NOT NULL CHECK(length(reason)<=2000 AND (action<>'REJECT' OR length(btrim(reason))>0)),
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,project_id,test_case_id,row_version),
 UNIQUE(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id,test_case_id,revision_id)
  REFERENCES test_revision(tenant_id,project_id,test_case_id,id)
);
CREATE INDEX test_review_history ON test_review_event(tenant_id,project_id,test_case_id,revision_id,row_version DESC);
CREATE TABLE test_review_outbox_event (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 project_id UUID NOT NULL,
 event_type TEXT NOT NULL CHECK(event_type IN ('test_case.review.submitted','test_case.review.approved','test_case.review.rejected')),
 payload JSONB NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(tenant_id,project_id,id) REFERENCES test_review_event(tenant_id,project_id,id)
);

-- A versioned digest of authoritative JSONB content, including ordered steps.
-- Invoker rights preserve all existing project RLS boundaries.
CREATE FUNCTION app_test_review_digest(t UUID,p UUID,c UUID,r UUID) RETURNS TEXT
LANGUAGE sql STABLE AS $$
 SELECT encode(sha256(convert_to(jsonb_build_object(
  'schema','TEST_REVIEW_CONTENT_V1','tenantId',tr.tenant_id,'projectId',tr.project_id,
  'testCaseId',tr.test_case_id,'revisionId',tr.id,'revisionNo',tr.revision_no,
  'testType',tc.test_type,'title',tr.title,'description',tr.description,
  'preconditions',tr.preconditions,'businessFields',tr.business_fields,
  'steps',COALESCE((SELECT jsonb_agg(jsonb_build_object('stepKey',s.step_key,'ordinal',s.ordinal,
    'action',s.action,'expected',s.expected) ORDER BY s.ordinal)
   FROM test_step s WHERE s.tenant_id=t AND s.project_id=p AND s.test_case_id=c AND s.revision_id=r),'[]'::jsonb)
 )::text,'UTF8')),'hex')
 FROM test_revision tr JOIN test_case tc ON tc.tenant_id=tr.tenant_id AND tc.project_id=tr.project_id AND tc.id=tr.test_case_id
 WHERE tr.tenant_id=t AND tr.project_id=p AND tr.test_case_id=c AND tr.id=r AND tr.sealed_at IS NOT NULL
$$;
REVOKE ALL ON FUNCTION app_test_review_digest(UUID,UUID,UUID,UUID) FROM PUBLIC;

ALTER TABLE test_review_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE test_review_outbox_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY review_read ON test_review_event FOR SELECT USING(app_core_scope(tenant_id,project_id));
CREATE POLICY review_insert ON test_review_event FOR INSERT WITH CHECK(
 app_core_scope(tenant_id,project_id)
 AND actor_id=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID
 AND app_has_active_project_writer(tenant_id,project_id,actor_id)
 AND app_has_active_project_writer(tenant_id,project_id,reviewer_id)
 AND (action='SUBMIT' OR actor_id=reviewer_id));
CREATE POLICY review_outbox_read ON test_review_outbox_event FOR SELECT USING(app_core_scope(tenant_id,project_id));
CREATE POLICY review_outbox_insert ON test_review_outbox_event FOR INSERT WITH CHECK(
 app_core_scope(tenant_id,project_id) AND EXISTS(SELECT 1 FROM test_review_event e
  WHERE e.tenant_id=test_review_outbox_event.tenant_id AND e.project_id=test_review_outbox_event.project_id
  AND e.id=test_review_outbox_event.id AND e.actor_id=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID
  AND app_has_active_project_writer(e.tenant_id,e.project_id,e.actor_id)));
CREATE POLICY review_audit_insert ON audit_event FOR INSERT WITH CHECK(
 app_core_scope(tenant_id,project_id) AND object_type='test_case'
 AND actor_principal_id=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID
 AND app_has_active_project_writer(tenant_id,project_id,actor_principal_id)
 AND EXISTS(SELECT 1 FROM test_review_event e WHERE e.id=audit_event.id
  AND e.tenant_id=audit_event.tenant_id AND e.project_id=audit_event.project_id
  AND e.test_case_id=audit_event.object_id AND e.revision_no=audit_event.object_revision
  AND e.actor_id=audit_event.actor_principal_id AND e.content_digest=audit_event.after_hash
  AND audit_event.action='test_case.review.'||lower(e.state)));

CREATE FUNCTION app_test_review_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE tc test_case; tr test_revision; prior TEXT;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'review history is immutable' USING ERRCODE='42501'; END IF;
 SELECT * INTO tc FROM test_case WHERE tenant_id=NEW.tenant_id AND project_id=NEW.project_id AND id=NEW.test_case_id FOR UPDATE;
 SELECT * INTO tr FROM test_revision WHERE tenant_id=NEW.tenant_id AND project_id=NEW.project_id
  AND test_case_id=NEW.test_case_id AND id=NEW.revision_id;
 IF tc.id IS NULL OR tr.id IS NULL OR tr.sealed_at IS NULL OR tc.current_revision_id<>NEW.revision_id
  OR tc.row_version<>NEW.row_version OR tr.revision_no<>NEW.revision_no
  OR NULLIF(tr.business_fields#>>'{reviewer,value}','')::UUID IS DISTINCT FROM NEW.reviewer_id
  OR NEW.content_digest IS DISTINCT FROM app_test_review_digest(NEW.tenant_id,NEW.project_id,NEW.test_case_id,NEW.revision_id)
  OR NEW.actor_id IS DISTINCT FROM NULLIF(current_setting('test365alm.principal_id',true),'')::UUID
  OR NOT app_has_active_project_writer(NEW.tenant_id,NEW.project_id,NEW.actor_id)
  OR NOT app_has_active_project_writer(NEW.tenant_id,NEW.project_id,NEW.reviewer_id)
  OR (NEW.action<>'SUBMIT' AND NEW.actor_id<>NEW.reviewer_id) THEN
  RAISE EXCEPTION 'invalid review identity or content binding' USING ERRCODE='23514';
 END IF;
 SELECT state INTO prior FROM test_review_event WHERE tenant_id=NEW.tenant_id AND project_id=NEW.project_id
  AND test_case_id=NEW.test_case_id AND revision_id=NEW.revision_id ORDER BY row_version DESC LIMIT 1;
 IF NOT COALESCE(((NEW.action='SUBMIT' AND COALESCE(prior,'DRAFT') IN ('DRAFT','REJECTED') AND NEW.state='SUBMITTED')
  OR (prior='SUBMITTED' AND NEW.action='APPROVE' AND NEW.state='APPROVED')
  OR (prior='SUBMITTED' AND NEW.action='REJECT' AND NEW.state='REJECTED')),false) THEN
  RAISE EXCEPTION 'invalid review transition' USING ERRCODE='23514';
 END IF;
 NEW.occurred_at:=CURRENT_TIMESTAMP;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION app_test_review_guard() FROM PUBLIC;
CREATE TRIGGER review_guard BEFORE INSERT OR UPDATE OR DELETE ON test_review_event FOR EACH ROW EXECUTE FUNCTION app_test_review_guard();

-- A review event cannot commit without its matching audit, outbox and frozen command.
CREATE FUNCTION app_test_review_commit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM audit_event a WHERE a.id=NEW.id AND a.tenant_id=NEW.tenant_id AND a.project_id=NEW.project_id
  AND a.object_id=NEW.test_case_id AND a.object_type='test_case' AND a.object_revision=NEW.revision_no
  AND a.actor_principal_id=NEW.actor_id AND a.after_hash=NEW.content_digest AND a.action='test_case.review.'||lower(NEW.state))
 OR NOT EXISTS(SELECT 1 FROM test_review_outbox_event o WHERE o.id=NEW.id AND o.tenant_id=NEW.tenant_id AND o.project_id=NEW.project_id
  AND o.event_type='test_case.review.'||lower(NEW.state) AND o.payload->>'id'=NEW.id::TEXT
  AND o.payload->>'contentDigest'=NEW.content_digest)
 OR NOT EXISTS(SELECT 1 FROM core_command_idempotency i WHERE i.tenant_id=NEW.tenant_id AND i.project_id=NEW.project_id
  AND i.principal_id=NEW.actor_id AND i.route='test-review:'||NEW.test_case_id||':'||NEW.revision_id
  AND i.response_json#>>'{latest,id}'=NEW.id::TEXT) THEN
  RAISE EXCEPTION 'review transaction is incomplete' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION app_test_review_commit() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER review_commit AFTER INSERT ON test_review_event DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION app_test_review_commit();
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='test365alm_runtime') THEN
 GRANT SELECT,INSERT ON test_review_event,test_review_outbox_event TO test365alm_runtime;
 GRANT EXECUTE ON FUNCTION app_test_review_digest(UUID,UUID,UUID,UUID) TO test365alm_runtime;
END IF; END $$;
