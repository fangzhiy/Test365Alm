-- Business values belong to exact immutable content revisions, not entity identity.
-- Narrow directory projection: does not grant member administration or expose tenant candidates.
CREATE FUNCTION app_business_reviewers(t UUID,p UUID,q TEXT,c UUID,n INTEGER,selected UUID,actor UUID)
RETURNS TABLE(principal_id UUID,display_name TEXT) LANGUAGE sql STABLE SECURITY DEFINER
SET search_path=pg_catalog,public AS $$
 SELECT m.principal_id,u.display_name FROM public.project_member m JOIN public.principal u ON u.id=m.principal_id
 WHERE public.app_core_scope(t,p)
 AND actor=NULLIF(current_setting('test365alm.principal_id',true),'')::UUID
 AND n BETWEEN 1 AND 101 AND length(q)<=200
 AND m.tenant_id=t AND m.project_id=p AND m.principal_id>c
 AND EXISTS(SELECT 1 FROM public.project pr JOIN public.domain d ON d.id=pr.domain_id AND d.tenant_id=pr.tenant_id
   JOIN public.tenant te ON te.id=pr.tenant_id WHERE pr.id=p AND pr.tenant_id=t
   AND pr.state='ACTIVE' AND d.status='ACTIVE' AND te.status='ACTIVE')
 AND (selected IS NULL OR m.principal_id=selected)
 AND public.app_has_active_project_writer(t,p,m.principal_id)
 AND strpos(lower(u.display_name),lower(q))>0
 ORDER BY m.principal_id LIMIT LEAST(GREATEST(n,0),101)
$$;
REVOKE ALL ON FUNCTION app_business_reviewers(UUID,UUID,TEXT,UUID,INTEGER,UUID,UUID) FROM PUBLIC;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='test365alm_runtime') THEN
 GRANT EXECUTE ON FUNCTION app_business_reviewers(UUID,UUID,TEXT,UUID,INTEGER,UUID,UUID) TO test365alm_runtime;
END IF; END $$;
ALTER TABLE test_revision ADD COLUMN business_fields JSONB NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(business_fields)='object');
ALTER TABLE requirement_revision ADD COLUMN business_fields JSONB NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(business_fields)='object');
ALTER TABLE defect_revision ADD COLUMN business_fields JSONB NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(business_fields)='object');
ALTER TABLE requirement_idempotency ADD COLUMN result_business_fields JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Invoker rights: existing project RLS also applies to the referenced metadata.
CREATE FUNCTION app_business_fields_integrity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE k TEXT; e JSONB; d JSONB; v JSONB; allowed TEXT[]; n NUMERIC; integer_digits INTEGER; scale_digits INTEGER;
BEGIN
 IF TG_OP='UPDATE' THEN
  IF NEW.business_fields IS DISTINCT FROM OLD.business_fields THEN
   RAISE EXCEPTION 'business fields are immutable' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END IF;
 allowed:=CASE TG_TABLE_NAME WHEN 'test_revision' THEN ARRAY['test_purpose','reviewer']
  WHEN 'requirement_revision' THEN ARRAY['equipment_system_id','owning_department']
  ELSE ARRAY['occurrence_department','occurrence_location','reviewer'] END;
 FOR k,e IN SELECT * FROM jsonb_each(NEW.business_fields) LOOP
  IF NOT k=ANY(allowed) OR jsonb_typeof(e) IS DISTINCT FROM 'object'
   OR EXISTS(SELECT 1 FROM jsonb_object_keys(e) x WHERE x NOT IN ('kind','fieldId','revision','definition','value'))
   OR jsonb_typeof(e->'revision') IS DISTINCT FROM 'number' OR (e->>'revision')!~'^[1-9][0-9]*$' THEN
   RAISE EXCEPTION 'invalid business field snapshot' USING ERRCODE='23514';
  END IF;
  IF e->>'kind'='CUSTOM' THEN
   SELECT r.definition INTO d FROM field_definition f JOIN field_definition_revision r
    ON r.tenant_id=f.tenant_id AND r.project_id=f.project_id AND r.field_id=f.id
    WHERE f.tenant_id=NEW.tenant_id AND f.project_id=NEW.project_id
     AND f.id=(e->>'fieldId')::UUID AND f.field_key=k AND r.revision_no=(e->>'revision')::BIGINT;
   IF d IS NULL THEN RAISE EXCEPTION 'invalid business field reference' USING ERRCODE='23514'; END IF;
  ELSIF e->>'kind'='SYSTEM' AND NOT e ? 'fieldId' AND e->>'revision'='1' THEN
   d:=jsonb_build_object('key',k,'type','TEXT','required',false,'maxLength',CASE k WHEN 'test_purpose' THEN 4000 WHEN 'reviewer' THEN 36 ELSE 200 END);
  ELSE RAISE EXCEPTION 'invalid business field definition kind' USING ERRCODE='23514'; END IF;
  IF e->'definition' IS DISTINCT FROM d OR NOT e ? 'value' THEN
   RAISE EXCEPTION 'business field definition mismatch' USING ERRCODE='23514';
  END IF;
  v:=e->'value';
  IF v='null'::jsonb THEN
   IF (d->>'required')::BOOLEAN OR COALESCE(d->'defaultValue','null'::jsonb)<>'null'::jsonb THEN
    RAISE EXCEPTION 'missing business field value' USING ERRCODE='23514';
   END IF;
  ELSE
   IF jsonb_typeof(v)<>'string' OR ((d->>'required')::BOOLEAN AND btrim(v#>>'{}')='') THEN
    RAISE EXCEPTION 'invalid business field value type' USING ERRCODE='23514';
   END IF;
   IF d->>'type'='TEXT' AND length(v#>>'{}')>(d->>'maxLength')::INTEGER THEN
    RAISE EXCEPTION 'business field text too long' USING ERRCODE='23514';
   ELSIF d->>'type'='ENUM' AND NOT (d->'options' @> jsonb_build_array(v)) THEN
    RAISE EXCEPTION 'business field enum value invalid' USING ERRCODE='23514';
   ELSIF d->>'type'='NUMBER' THEN
    IF length(v#>>'{}')>100 OR (v#>>'{}')!~'^-?(0|[1-9][0-9]*)(\.[0-9]+)?$' THEN
     RAISE EXCEPTION 'invalid business field decimal' USING ERRCODE='23514'; END IF;
    n:=(v#>>'{}')::NUMERIC;scale_digits:=CASE WHEN position('.' IN (v#>>'{}'))>0 THEN length(split_part(v#>>'{}','.',2)) ELSE 0 END;
    integer_digits:=CASE WHEN n=0 THEN 0 ELSE length(ltrim(split_part(abs(n)::TEXT,'.',1),'0')) END;
    IF scale_digits>(d->>'scale')::INTEGER OR integer_digits>(d->>'precision')::INTEGER-(d->>'scale')::INTEGER
     OR (d ? 'minimum' AND n<(d->>'minimum')::NUMERIC) OR (d ? 'maximum' AND n>(d->>'maximum')::NUMERIC) THEN
     RAISE EXCEPTION 'business field decimal outside bounds' USING ERRCODE='23514'; END IF;
   END IF;
   IF k='reviewer' AND btrim(v#>>'{}')<>'' AND NOT app_has_active_project_writer(NEW.tenant_id,NEW.project_id,(v#>>'{}')::UUID) THEN
    RAISE EXCEPTION 'reviewer must be an active project writer' USING ERRCODE='23514'; END IF;
  END IF;
 END LOOP;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION app_business_fields_integrity() FROM PUBLIC;
CREATE TRIGGER business_fields_integrity BEFORE INSERT OR UPDATE ON test_revision FOR EACH ROW EXECUTE FUNCTION app_business_fields_integrity();
CREATE TRIGGER business_fields_integrity BEFORE INSERT OR UPDATE ON requirement_revision FOR EACH ROW EXECUTE FUNCTION app_business_fields_integrity();
CREATE TRIGGER business_fields_integrity BEFORE INSERT OR UPDATE ON defect_revision FOR EACH ROW EXECUTE FUNCTION app_business_fields_integrity();
-- Existing SELECT/INSERT table grants extend to the additive columns; no historical UPDATE capability is added.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='test365alm_runtime') THEN
  GRANT UPDATE(result_business_fields) ON requirement_idempotency TO test365alm_runtime;
 END IF;
END $$;
