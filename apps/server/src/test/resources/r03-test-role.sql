-- Synthetic credential for a disposable Testcontainers database only.
CREATE ROLE test365alm_runtime LOGIN PASSWORD 'r03_isolated_test_role_only';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
