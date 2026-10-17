-- Authoritative capability/login provisioning. Called inside an owner/operator transaction.
-- The caller supplies a validated login and a client-generated SCRAM verifier, never plaintext.
-- Existing safe logins are verified, not rotated; unsafe existing roles are never repaired here.
DO $provision_scanner$
DECLARE
  login_name text := pg_catalog.current_setting('test365alm.provision_scanner_login');
  verifier text := pg_catalog.current_setting('test365alm.provision_scanner_verifier');
  operator_record record;
  group_record record;
  login_record record;
  database_owner oid;
BEGIN
  PERFORM pg_catalog.pg_advisory_xact_lock(73112021001);
  SELECT * INTO STRICT operator_record FROM pg_catalog.pg_roles WHERE rolname=current_user;
  SELECT datdba INTO STRICT database_owner FROM pg_catalog.pg_database WHERE datname=pg_catalog.current_database();
  IF current_user <> session_user OR (NOT operator_record.rolsuper AND NOT (operator_record.rolcreaterole AND
      (operator_record.oid=database_owner OR EXISTS (
       SELECT 1 FROM pg_catalog.pg_auth_members m JOIN pg_catalog.pg_roles r ON r.oid=m.roleid
        WHERE r.rolname='test365alm_scanner' AND m.member=operator_record.oid AND m.admin_option)))) THEN
    RAISE EXCEPTION USING ERRCODE='42501', MESSAGE='Scanner provisioning requires the authorized database operator';
  END IF;
  IF login_name !~ '^[a-z][a-z0-9_]{7,62}$' OR login_name LIKE 'pg_%'
      OR login_name IN ('test365alm_scanner','test365alm_runtime',current_user,session_user)
      OR verifier !~ '^SCRAM-SHA-256\$4096:[A-Za-z0-9+/]+=*\$[A-Za-z0-9+/]+=*:[A-Za-z0-9+/]+=*$' THEN
    RAISE EXCEPTION USING ERRCODE='22023', MESSAGE='Invalid scanner provisioning input';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname='test365alm_scanner') THEN
    CREATE ROLE test365alm_scanner NOLOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  END IF;
  SELECT * INTO STRICT group_record FROM pg_catalog.pg_roles WHERE rolname='test365alm_scanner';
  IF group_record.rolsuper OR group_record.rolcreatedb OR group_record.rolcreaterole
      OR group_record.rolreplication OR group_record.rolbypassrls
      OR EXISTS (SELECT 1 FROM pg_catalog.pg_db_role_setting WHERE setrole=group_record.oid AND cardinality(setconfig)>0)
      OR EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members WHERE member=group_record.oid)
      OR EXISTS (SELECT 1 FROM pg_catalog.pg_shdepend WHERE refclassid='pg_catalog.pg_authid'::pg_catalog.regclass AND refobjid=group_record.oid AND deptype='o') THEN
    RAISE EXCEPTION USING ERRCODE='42501', MESSAGE='Existing scanner capability role is not restricted';
  END IF;
  -- Direct grants must stay within V20's narrow capability set. Merely belonging to
  -- a role named scanner is insufficient when that role was previously expanded.
  IF EXISTS (SELECT 1 FROM pg_catalog.pg_shdepend d
      WHERE d.refclassid='pg_catalog.pg_authid'::pg_catalog.regclass AND d.refobjid=group_record.oid AND d.deptype='a'
        AND NOT (d.dbid=(SELECT oid FROM pg_catalog.pg_database WHERE datname=pg_catalog.current_database()) AND d.objsubid=0 AND (
          (d.classid='pg_catalog.pg_namespace'::pg_catalog.regclass AND d.objid='public'::pg_catalog.regnamespace)
          OR (d.classid='pg_catalog.pg_class'::pg_catalog.regclass AND d.objid IN (pg_catalog.to_regclass('public.upload_session'),pg_catalog.to_regclass('public.scan_result')))
          OR (d.classid='pg_catalog.pg_proc'::pg_catalog.regclass AND d.objid IN (
            pg_catalog.to_regprocedure('public.app_core_scope(uuid,uuid)'),
            pg_catalog.to_regprocedure('public.app_has_active_project_member(uuid,uuid,uuid)'),
            pg_catalog.to_regprocedure('public.app_has_active_project_writer(uuid,uuid,uuid)'))))))
      OR EXISTS (SELECT 1 FROM pg_catalog.pg_namespace n, LATERAL pg_catalog.aclexplode(n.nspacl) a
        WHERE a.grantee=group_record.oid AND (n.nspname<>'public' OR a.privilege_type<>'USAGE' OR a.is_grantable))
      OR EXISTS (SELECT 1 FROM pg_catalog.pg_class c, LATERAL pg_catalog.aclexplode(c.relacl) a
        WHERE a.grantee=group_record.oid AND (a.is_grantable OR NOT (
          (c.oid=pg_catalog.to_regclass('public.upload_session') AND a.privilege_type='SELECT') OR
          (c.oid=pg_catalog.to_regclass('public.scan_result') AND a.privilege_type IN ('SELECT','INSERT')))))
      OR EXISTS (SELECT 1 FROM pg_catalog.pg_proc p, LATERAL pg_catalog.aclexplode(p.proacl) a
        WHERE a.grantee=group_record.oid AND (a.is_grantable OR a.privilege_type<>'EXECUTE' OR p.oid NOT IN (
          pg_catalog.to_regprocedure('public.app_core_scope(uuid,uuid)'),
          pg_catalog.to_regprocedure('public.app_has_active_project_member(uuid,uuid,uuid)'),
          pg_catalog.to_regprocedure('public.app_has_active_project_writer(uuid,uuid,uuid)')))) THEN
    RAISE EXCEPTION USING ERRCODE='42501', MESSAGE='Existing scanner capability ACL exceeds the published boundary';
  END IF;
  SELECT * INTO login_record FROM pg_catalog.pg_roles WHERE rolname=login_name;
  IF FOUND THEN
    IF NOT login_record.rolcanlogin OR NOT login_record.rolinherit OR login_record.rolsuper
        OR login_record.rolcreatedb OR login_record.rolcreaterole OR login_record.rolreplication OR login_record.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_db_role_setting WHERE setrole=login_record.oid AND cardinality(setconfig)>0)
        OR (SELECT count(*) FROM pg_catalog.pg_auth_members WHERE member=login_record.oid)<>1
        OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members WHERE member=login_record.oid AND roleid=group_record.oid AND NOT admin_option AND inherit_option)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_shdepend WHERE refclassid='pg_catalog.pg_authid'::pg_catalog.regclass AND refobjid=login_record.oid AND deptype IN ('o','a')) THEN
      RAISE EXCEPTION USING ERRCODE='42501', MESSAGE='Existing scanner login is not restricted; no changes made';
    END IF;
    -- The caller subsequently authenticates using the supplied secret. Never silently rotate it.
    RETURN;
  END IF;
  EXECUTE pg_catalog.format('CREATE ROLE %I LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 4 PASSWORD %L IN ROLE test365alm_scanner',login_name,verifier);
END
$provision_scanner$;
