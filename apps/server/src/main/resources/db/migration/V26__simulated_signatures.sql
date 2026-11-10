-- Additive E slice. Existing content, execution and review migrations remain byte-identical.
CREATE FUNCTION app_signature_snapshot(t UUID,p UUID,k TEXT,o UUID,s UUID) RETURNS JSONB
LANGUAGE plpgsql STABLE AS $$
DECLARE content JSONB;
BEGIN
 IF k='TEST_APPROVAL' THEN
  SELECT jsonb_build_object('revision',to_jsonb(r),'testType',c.test_type,
   'review',(SELECT to_jsonb(e) FROM test_review_event e WHERE e.tenant_id=t AND e.project_id=p
     AND e.test_case_id=o AND e.revision_id=s ORDER BY e.row_version DESC LIMIT 1),
   'steps',COALESCE((SELECT jsonb_agg(to_jsonb(v) ORDER BY v.ordinal) FROM test_step v
     WHERE v.tenant_id=t AND v.project_id=p AND v.test_case_id=o AND v.revision_id=s),'[]'::jsonb)) INTO content
  FROM test_revision r JOIN test_case c ON c.tenant_id=t AND c.project_id=p AND c.id=r.test_case_id
  WHERE r.tenant_id=t AND r.project_id=p AND r.test_case_id=o AND r.id=s AND r.sealed_at IS NOT NULL;
 ELSIF k='RUN_COMPLETION' THEN
  SELECT jsonb_build_object('manifest',to_jsonb(m),'attempt',to_jsonb(a),
   'steps',COALESCE((SELECT jsonb_agg(to_jsonb(v)||jsonb_build_object('attachments',COALESCE((
    SELECT jsonb_agg(jsonb_build_object('linkId',l.id,'assetId',l.asset_id,'revisionId',l.asset_revision_id,
     'sha256',ar.sha256,'size',ar.size,'filename',ar.filename,'mime',ar.mime) ORDER BY l.id)
    FROM attachment_link l JOIN asset_revision ar ON ar.tenant_id=l.tenant_id AND ar.project_id=l.project_id
     AND ar.asset_id=l.asset_id AND ar.id=l.asset_revision_id
    WHERE l.tenant_id=t AND l.project_id=p AND l.run_step_id=v.id),'[]'::jsonb)) ORDER BY v.ordinal)
    FROM run_step v WHERE v.tenant_id=t AND v.project_id=p AND v.attempt_id=s),'[]'::jsonb)) INTO content
  FROM run_attempt a JOIN execution_manifest m ON m.tenant_id=t AND m.project_id=p AND m.id=a.manifest_id
  WHERE a.tenant_id=t AND a.project_id=p AND a.run_id=o AND a.id=s;
 ELSIF k='DEVIATION_CLOSURE' THEN
  SELECT jsonb_build_object('revision',to_jsonb(r),'resolutionRevisionId',d.resolved_revision_id,
   'links',COALESCE((SELECT jsonb_agg(to_jsonb(l) ORDER BY l.id) FROM defect_link l
    WHERE l.tenant_id=t AND l.project_id=p AND l.defect_id=o),'[]'::jsonb),
   'retests',COALESCE((SELECT jsonb_agg(to_jsonb(v)||jsonb_build_object('attempt',to_jsonb(a)) ORDER BY v.id)
    FROM defect_retest v JOIN run_attempt a ON a.tenant_id=t AND a.project_id=p AND a.run_id=v.run_id AND a.id=v.attempt_id
    WHERE v.tenant_id=t AND v.project_id=p AND v.defect_id=o AND v.resolved_revision_id=d.resolved_revision_id),'[]'::jsonb)) INTO content
  FROM defect_revision r JOIN defect d ON d.tenant_id=t AND d.project_id=p AND d.id=r.defect_id
  WHERE r.tenant_id=t AND r.project_id=p AND r.defect_id=o AND r.id=s;
 END IF;
 IF content IS NULL THEN RETURN NULL; END IF;
 RETURN jsonb_build_object('schema','SIGNATURE_SNAPSHOT_V1','tenantId',t,'projectId',p,'kind',k,'objectId',o,'snapshotId',s,'content',content);
END $$;
REVOKE ALL ON FUNCTION app_signature_snapshot(UUID,UUID,TEXT,UUID,UUID) FROM PUBLIC;

CREATE FUNCTION app_signature_version(t UUID,p UUID,k TEXT,o UUID,s UUID,actor UUID) RETURNS BIGINT
LANGUAGE plpgsql STABLE AS $$
DECLARE v BIGINT;
BEGIN
 IF NOT app_core_scope(t,p) OR NOT app_has_active_project_writer(t,p,actor) THEN RETURN NULL; END IF;
 IF k='TEST_APPROVAL' THEN
  SELECT c.row_version INTO v FROM test_case c JOIN test_revision r ON r.tenant_id=t AND r.project_id=p AND r.test_case_id=c.id AND r.id=s
  WHERE c.tenant_id=t AND c.project_id=p AND c.id=o AND c.current_revision_id=s AND r.sealed_at IS NOT NULL
   AND NULLIF(r.business_fields#>>'{reviewer,value}','')::UUID=actor
   AND (SELECT e.state FROM test_review_event e WHERE e.tenant_id=t AND e.project_id=p AND e.test_case_id=o
    AND e.revision_id=s ORDER BY e.row_version DESC LIMIT 1)='APPROVED';
 ELSIF k='RUN_COMPLETION' THEN
  SELECT row_version INTO v FROM run_attempt WHERE tenant_id=t AND project_id=p AND run_id=o AND id=s AND status='FINISHED';
 ELSIF k='DEVIATION_CLOSURE' THEN
  SELECT d.row_version INTO v FROM defect d JOIN defect_revision r ON r.tenant_id=t AND r.project_id=p AND r.defect_id=d.id AND r.id=s
  WHERE d.tenant_id=t AND d.project_id=p AND d.id=o AND d.current_revision_id=s AND d.status='CLOSED' AND r.status='CLOSED';
 END IF;
 RETURN v;
END $$;
REVOKE ALL ON FUNCTION app_signature_version(UUID,UUID,TEXT,UUID,UUID,UUID) FROM PUBLIC;

CREATE TABLE signature_challenge (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,project_id UUID NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN('TEST_APPROVAL','RUN_COMPLETION','DEVIATION_CLOSURE')),
 object_id UUID NOT NULL,snapshot_id UUID NOT NULL,source_version BIGINT NOT NULL CHECK(source_version>0),
 actor_id UUID NOT NULL REFERENCES principal(id),issuer TEXT NOT NULL,subject TEXT NOT NULL,display_name TEXT NOT NULL,
 session_binding TEXT NOT NULL CHECK(session_binding~'^[a-f0-9]{64}$'),
 content_snapshot JSONB NOT NULL,content_digest TEXT NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,project_id,id),FOREIGN KEY(tenant_id,project_id) REFERENCES project(tenant_id,id)
);
CREATE TABLE signature_record (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,project_id UUID NOT NULL,challenge_id UUID NOT NULL UNIQUE,
 kind TEXT NOT NULL,object_id UUID NOT NULL,snapshot_id UUID NOT NULL,actor_id UUID NOT NULL REFERENCES principal(id),
 content_digest TEXT NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),payload JSONB NOT NULL,
 UNIQUE(tenant_id,project_id,id),UNIQUE(tenant_id,project_id,kind,object_id,snapshot_id),
 FOREIGN KEY(tenant_id,project_id,challenge_id) REFERENCES signature_challenge(tenant_id,project_id,id)
);
CREATE TABLE signature_outbox_event (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,project_id UUID NOT NULL,event_type TEXT NOT NULL CHECK(event_type='signature.signed'),
 payload JSONB NOT NULL,occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(tenant_id,project_id,id) REFERENCES signature_record(tenant_id,project_id,id)
);
DO $$ DECLARE tbl TEXT; BEGIN
 FOREACH tbl IN ARRAY ARRAY['signature_challenge','signature_record','signature_outbox_event'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY signature_read ON %I FOR SELECT USING(app_core_scope(tenant_id,project_id))',tbl);
 END LOOP;
END $$;
CREATE POLICY signature_challenge_insert ON signature_challenge FOR INSERT WITH CHECK(app_core_scope(tenant_id,project_id)
 AND actor_id=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID AND app_has_active_project_writer(tenant_id,project_id,actor_id));
CREATE POLICY signature_record_insert ON signature_record FOR INSERT WITH CHECK(app_core_scope(tenant_id,project_id)
 AND actor_id=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID AND app_has_active_project_writer(tenant_id,project_id,actor_id));
CREATE POLICY signature_outbox_insert ON signature_outbox_event FOR INSERT WITH CHECK(app_core_scope(tenant_id,project_id)
 AND EXISTS(SELECT 1 FROM signature_record r WHERE r.tenant_id=signature_outbox_event.tenant_id AND r.project_id=signature_outbox_event.project_id
  AND r.id=signature_outbox_event.id AND r.actor_id=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID));
CREATE POLICY signature_audit_insert ON audit_event FOR INSERT WITH CHECK(app_core_scope(tenant_id,project_id)
 AND object_type='signature' AND action='signature.signed' AND actor_principal_id=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID
 AND EXISTS(SELECT 1 FROM signature_record r WHERE r.id=audit_event.id AND r.tenant_id=audit_event.tenant_id AND r.project_id=audit_event.project_id
  AND r.id=audit_event.object_id AND r.actor_id=audit_event.actor_principal_id AND r.content_digest=audit_event.after_hash));

CREATE FUNCTION app_signature_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'signature evidence is immutable' USING ERRCODE='42501'; END $$;
REVOKE ALL ON FUNCTION app_signature_immutable() FROM PUBLIC;
DO $$ DECLARE tbl TEXT; BEGIN
 FOREACH tbl IN ARRAY ARRAY['signature_challenge','signature_record','signature_outbox_event'] LOOP
  EXECUTE format('CREATE TRIGGER signature_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION app_signature_immutable()',tbl);
  EXECUTE format('CREATE TRIGGER signature_no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION app_signature_immutable()',tbl);
 END LOOP;
END $$;
CREATE FUNCTION app_signature_challenge_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.actor_id IS DISTINCT FROM NULLIF(current_setting('test365alm.principal_id',true),'')::UUID
  OR NEW.source_version IS DISTINCT FROM app_signature_version(NEW.tenant_id,NEW.project_id,NEW.kind,NEW.object_id,NEW.snapshot_id,NEW.actor_id)
  OR NEW.content_snapshot IS DISTINCT FROM app_signature_snapshot(NEW.tenant_id,NEW.project_id,NEW.kind,NEW.object_id,NEW.snapshot_id)
  OR NEW.content_digest IS DISTINCT FROM encode(sha256(convert_to(NEW.content_snapshot::text,'UTF8')),'hex')
  OR NOT EXISTS(SELECT 1 FROM principal pr WHERE pr.id=NEW.actor_id AND pr.disabled_at IS NULL
    AND pr.issuer=NEW.issuer AND pr.subject=NEW.subject AND pr.display_name=NEW.display_name) THEN
  RAISE EXCEPTION 'invalid signature challenge binding' USING ERRCODE='23514'; END IF;
 NEW.created_at:=CURRENT_TIMESTAMP;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION app_signature_challenge_guard() FROM PUBLIC;
CREATE TRIGGER signature_challenge_rules BEFORE INSERT ON signature_challenge FOR EACH ROW EXECUTE FUNCTION app_signature_challenge_guard();

CREATE FUNCTION app_signature_record_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c signature_challenge; material JSONB; auth_time TIMESTAMPTZ;
BEGIN
 SELECT * INTO c FROM signature_challenge WHERE tenant_id=NEW.tenant_id AND project_id=NEW.project_id AND id=NEW.challenge_id;
 material:=NEW.payload;auth_time:=(material->>'authenticatedAt')::TIMESTAMPTZ;
 IF c.id IS NULL OR c.actor_id IS DISTINCT FROM NEW.actor_id OR c.kind IS DISTINCT FROM NEW.kind
  OR c.object_id IS DISTINCT FROM NEW.object_id OR c.snapshot_id IS DISTINCT FROM NEW.snapshot_id
  OR c.content_digest IS DISTINCT FROM NEW.content_digest OR CURRENT_TIMESTAMP>=c.created_at+INTERVAL '5 minutes'
  OR c.source_version IS DISTINCT FROM app_signature_version(c.tenant_id,c.project_id,c.kind,c.object_id,c.snapshot_id,c.actor_id)
  OR c.content_snapshot IS DISTINCT FROM app_signature_snapshot(c.tenant_id,c.project_id,c.kind,c.object_id,c.snapshot_id)
  OR material->>'schema' IS DISTINCT FROM 'SIGNATURE_RECORD_V1'
  OR material->>'id' IS DISTINCT FROM NEW.id::TEXT OR material->>'projectId' IS DISTINCT FROM NEW.project_id::TEXT
  OR material->>'actorId' IS DISTINCT FROM c.actor_id::TEXT OR material->>'issuer' IS DISTINCT FROM c.issuer
  OR material->>'subject' IS DISTINCT FROM c.subject OR material->>'displayName' IS DISTINCT FROM c.display_name
  OR material->>'kind' IS DISTINCT FROM c.kind OR material->>'objectId' IS DISTINCT FROM c.object_id::TEXT
  OR material->>'snapshotId' IS DISTINCT FROM c.snapshot_id::TEXT OR (material->>'sourceVersion')::BIGINT IS DISTINCT FROM c.source_version
  OR material->>'contentDigest' IS DISTINCT FROM c.content_digest OR material->'contentSnapshot' IS DISTINCT FROM c.content_snapshot
  OR material->>'meaning' IS DISTINCT FROM CASE c.kind WHEN 'TEST_APPROVAL' THEN '用例批准确认' WHEN 'RUN_COMPLETION' THEN '运行完成确认' ELSE '偏差关闭确认' END
  OR (material->>'signedAt')::TIMESTAMPTZ IS DISTINCT FROM CURRENT_TIMESTAMP
  OR auth_time IS NULL OR auth_time<date_trunc('second',c.created_at) OR auth_time<CURRENT_TIMESTAMP-INTERVAL '2 minutes'
  OR auth_time>CURRENT_TIMESTAMP+INTERVAL '30 seconds'
  OR material#>>'{evidence,provider}' IS DISTINCT FROM 'SIMULATED_V1'
  OR material#>>'{evidence,simulated}' IS DISTINCT FROM 'true' OR material#>>'{evidence,reference}' IS DISTINCT FROM NEW.id::TEXT
  OR NOT COALESCE(material#>>'{evidence,bindingDigest}' ~ '^[a-f0-9]{64}$',false) THEN
  RAISE EXCEPTION 'invalid signature record binding' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION app_signature_record_guard() FROM PUBLIC;
CREATE TRIGGER signature_record_rules BEFORE INSERT ON signature_record FOR EACH ROW EXECUTE FUNCTION app_signature_record_guard();
CREATE FUNCTION app_signature_commit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM audit_event a WHERE a.id=NEW.id AND a.tenant_id=NEW.tenant_id AND a.project_id=NEW.project_id
  AND a.actor_principal_id=NEW.actor_id AND a.object_type='signature' AND a.object_id=NEW.id AND a.action='signature.signed'
  AND a.after_hash=NEW.content_digest)
 OR NOT EXISTS(SELECT 1 FROM signature_outbox_event e WHERE e.id=NEW.id AND e.tenant_id=NEW.tenant_id AND e.project_id=NEW.project_id AND e.payload=NEW.payload)
 OR NOT EXISTS(SELECT 1 FROM core_command_idempotency i WHERE i.tenant_id=NEW.tenant_id AND i.project_id=NEW.project_id AND i.principal_id=NEW.actor_id
  AND i.route='signature:sign:'||NEW.kind||':'||NEW.object_id||':'||NEW.snapshot_id AND i.response_json#>>'{signature,id}'=NEW.id::TEXT) THEN
  RAISE EXCEPTION 'signature transaction is incomplete' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION app_signature_commit() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER signature_commit AFTER INSERT ON signature_record DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION app_signature_commit();
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='test365alm_runtime') THEN
 GRANT SELECT,INSERT ON signature_challenge,signature_record,signature_outbox_event TO test365alm_runtime;
 GRANT EXECUTE ON FUNCTION app_signature_snapshot(UUID,UUID,TEXT,UUID,UUID),app_signature_version(UUID,UUID,TEXT,UUID,UUID,UUID) TO test365alm_runtime;
END IF; END $$;
