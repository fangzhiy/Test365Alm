-- R11 FIX01: validate final committed publication state, not a mutable allocator alone.
-- Flyway runs this additive migration transactionally. Block concurrent writes while
-- inspecting legacy V20 rows; refuse invalid data rather than rewriting history.
LOCK TABLE public.asset, public.asset_revision IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.asset a
        CROSS JOIN LATERAL (
            SELECT COUNT(*) AS revision_count, MIN(r.revision_no) AS first_revision,
                   MAX(r.revision_no) AS last_revision
            FROM public.asset_revision r
            WHERE r.tenant_id = a.tenant_id AND r.project_id = a.project_id AND r.asset_id = a.id
        ) history
        LEFT JOIN public.asset_revision current_revision
          ON current_revision.tenant_id = a.tenant_id AND current_revision.project_id = a.project_id
         AND current_revision.asset_id = a.id AND current_revision.id = a.current_revision_id
        WHERE history.revision_count = 0 OR history.first_revision IS DISTINCT FROM 1
           OR history.last_revision IS DISTINCT FROM history.revision_count
           OR a.next_revision_no - 1 IS DISTINCT FROM history.last_revision
           OR current_revision.revision_no IS DISTINCT FROM history.last_revision
    ) THEN
        RAISE EXCEPTION 'existing asset revision integrity invalid; migration refused without modifying data'
            USING ERRCODE = '23514';
    END IF;
END $$;

CREATE FUNCTION public.asset_revision_commit_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
DECLARE
    target_asset UUID;
    current_revision UUID;
    next_revision BIGINT;
    revision_count BIGINT;
    first_revision BIGINT;
    last_revision BIGINT;
    current_number BIGINT;
BEGIN
    IF TG_TABLE_SCHEMA <> 'public' OR TG_TABLE_NAME NOT IN ('asset', 'asset_revision') THEN
        RAISE EXCEPTION 'unexpected asset integrity trigger target' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'asset' THEN
        target_asset := NEW.id;
    ELSE
        target_asset := NEW.asset_id;
    END IF;

    -- A single publish performs multiple intermediate writes. Always read the
    -- final row, lock the same asset, and retain invoker RLS (no owner bypass).
    SELECT a.current_revision_id, a.next_revision_no
      INTO current_revision, next_revision
      FROM public.asset a
     WHERE a.tenant_id = NEW.tenant_id AND a.project_id = NEW.project_id AND a.id = target_asset
     FOR NO KEY UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'asset committed revision scope is unavailable'
            USING ERRCODE = '23514';
    END IF;

    SELECT COUNT(*), MIN(r.revision_no), MAX(r.revision_no)
      INTO revision_count, first_revision, last_revision
      FROM public.asset_revision r
     WHERE r.tenant_id = NEW.tenant_id AND r.project_id = NEW.project_id AND r.asset_id = target_asset;
    SELECT r.revision_no INTO current_number
      FROM public.asset_revision r
     WHERE r.tenant_id = NEW.tenant_id AND r.project_id = NEW.project_id
       AND r.asset_id = target_asset AND r.id = current_revision;

    -- V20 already guarantees positive, unique revision numbers within an asset.
    -- max=count plus min=1 therefore proves there are no missing revision numbers.
    -- next-1 avoids overflow when rejecting a malicious maximum BIGINT allocator.
    IF revision_count = 0 OR first_revision IS DISTINCT FROM 1
       OR last_revision IS DISTINCT FROM revision_count
       OR next_revision - 1 IS DISTINCT FROM last_revision
       OR current_number IS DISTINCT FROM last_revision THEN
        RAISE EXCEPTION 'asset committed revisions must be contiguous and current must be latest'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;

REVOKE ALL ON FUNCTION public.asset_revision_commit_guard() FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        GRANT EXECUTE ON FUNCTION public.asset_revision_commit_guard() TO test365alm_runtime;
    END IF;
END $$;

CREATE CONSTRAINT TRIGGER asset_publication_commit_integrity
AFTER INSERT OR UPDATE ON public.asset
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION public.asset_revision_commit_guard();

CREATE CONSTRAINT TRIGGER asset_revision_publication_commit_integrity
AFTER INSERT ON public.asset_revision
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION public.asset_revision_commit_guard();
