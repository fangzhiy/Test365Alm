#!/usr/bin/env python3
"""Create local-only R03 credentials and a disposable Keycloak realm import."""
from __future__ import annotations

import json
import os
import secrets
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENV = ROOT / ".env.r03"
REALM = ROOT / "local-evidence" / "r03" / "realm.json"


def main() -> int:
    if ENV.exists() or REALM.exists():
        print("Existing R03 local config preserved; remove it manually only after checking ownership.")
        return 2
    REALM.parent.mkdir(parents=True, exist_ok=True)
    migration_password = secrets.token_urlsafe(32)
    runtime_password = secrets.token_urlsafe(32)
    admin_password = secrets.token_urlsafe(32)
    demo_password = secrets.token_urlsafe(24)
    values = {
        "R03_DB_NAME": "test365alm_r03",
        "R03_MIGRATION_USER": "test365alm_migrator",
        "R03_MIGRATION_PASSWORD": migration_password,
        "R03_RUNTIME_PASSWORD": runtime_password,
        "R03_POSTGRES_PORT": "54339",
        "R03_KEYCLOAK_PORT": "18090",
        "R03_KEYCLOAK_ADMIN_USER": "r03-local-admin",
        "R03_KEYCLOAK_ADMIN_PASSWORD": admin_password,
        "R03_TEST_USER": "r03-user",
        "R03_TEST_USER_PASSWORD": demo_password,
        "TEST365ALM_DATASOURCE_URL": "jdbc:postgresql://127.0.0.1:54339/test365alm_r03",
        "TEST365ALM_DATASOURCE_USERNAME": "test365alm_runtime",
        "TEST365ALM_DATASOURCE_PASSWORD": runtime_password,
        "SPRING_FLYWAY_URL": "jdbc:postgresql://127.0.0.1:54339/test365alm_r03",
        "SPRING_FLYWAY_USER": "test365alm_migrator",
        "SPRING_FLYWAY_PASSWORD": migration_password,
        "TEST365ALM_OIDC_ISSUER": "http://127.0.0.1:18090/realms/test365alm",
        "SPRING_PROFILES_ACTIVE": "oidc",
    }
    realm = {
        "realm": "test365alm", "enabled": True,
        "clients": [{
            "clientId": "test365alm-web", "name": "R03 local BFF", "enabled": True,
            "publicClient": True, "standardFlowEnabled": True, "directAccessGrantsEnabled": False,
            "redirectUris": ["http://127.0.0.1:5173/login/oauth2/code/test365alm"],
            "webOrigins": ["http://127.0.0.1:5173"],
            "attributes": {"pkce.code.challenge.method": "S256"},
        }],
        "users": [{
            "username": "r03-user", "enabled": True, "firstName": "R03", "lastName": "Tester",
            "email": "r03-user@example.invalid", "emailVerified": True,
            "credentials": [{"type": "password", "value": demo_password, "temporary": False}],
        }],
    }
    REALM.write_text(json.dumps(realm, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    ENV.write_text("\n".join(f"{key}={value}" for key, value in values.items()) + "\n", encoding="utf-8")
    if os.name == "posix":
        REALM.chmod(0o600)
        ENV.chmod(0o600)
    print("Created ignored .env.r03 and local-evidence/r03/realm.json; both contain disposable test credentials.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
