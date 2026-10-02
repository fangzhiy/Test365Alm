-- V7/V8 idempotency rows did not carry an algorithm/version marker and
-- therefore cannot be replayed safely after the length-prefixed hash format
-- became authoritative.  Existing rows are conservatively marked as legacy;
-- the application only enables replay after it has written a complete
-- current-format frozen response.  This is a deliberate safe rejection, not
-- a claim of lossless V7 response compatibility.
ALTER TABLE requirement_idempotency
    ADD COLUMN replay_compatible BOOLEAN NOT NULL DEFAULT FALSE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test365alm_runtime') THEN
        -- V8 deliberately grants column-level UPDATE.  A new column is not
        -- covered by that grant, so keep the restricted runtime snapshot
        -- write path usable without granting broad table UPDATE.
        GRANT UPDATE (replay_compatible) ON requirement_idempotency TO test365alm_runtime;
    END IF;
END $$;
