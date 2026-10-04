-- The execution service removes expired idempotency rows inside the same
-- project-scoped runtime transaction before inserting a new key.  Keep this
-- permission limited to the execution idempotency table; RLS still requires
-- the caller to be an active writer for the exact tenant/project/principal.
GRANT DELETE ON execution_idempotency TO test365alm_runtime;
