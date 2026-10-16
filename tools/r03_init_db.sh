#!/bin/sh
set -eu
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 --set=runtime_password="$POSTGRES_RUNTIME_PASSWORD" <<'SQL'
CREATE ROLE test365alm_runtime LOGIN PASSWORD :'runtime_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
if [ -n "${POSTGRES_SCANNER_PASSWORD:-}" ]; then
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=ON_ERROR_STOP=1 --set=scanner_password="$POSTGRES_SCANNER_PASSWORD" <<'SQL'
CREATE ROLE test365alm_scanner LOGIN PASSWORD :'scanner_password';
SQL
fi
