---
title: Docker context lookup needs its profile even in a filtered scanner subprocess
author: "周"
date: 2026-11-10
branch: feat-execution-grid
id: l-2eac2b02
scope: repo
status: active
src: agent
touches:
  - tools/local_browser_assets.py
  - tools/tests/test_local_browser_assets.py
  - tools/provision_asset_scanner_role.py
---

## Decided
The formal scanner tool's container subprocess must preserve HOME/USERPROFILE/HOMEDRIVE/HOMEPATH from the caller solely for Docker context configuration lookup. Keep the pinned context; never fall back to default or forward those directory values into PostgreSQL.

The owned browser PostgreSQL initialization explicitly uses --auth-host=scram-sha-256 and POSTGRES_HOST_AUTH_METHOD=scram-sha-256 before creating the fresh disposable volume. Conflicting settings are refused; existing/user HBA files are never rewritten.

## Tried and ruled out
PROCESS/UNKNOWN alone does not prove SQL ran. Read-only probes of the same desktop-linux context and expected Engine succeeded with the browser parent environment, failed after the formal tool's filtering, and succeeded after restoring only the profile variables. No resource was created and no raw stderr/environment values were exported.

## Watch out
The missing profile was the demonstrated process-level fault. The separate lack of explicit loopback SCRAM initialization was found statically and remains pending real provision/verification; do not label it a second observed failure. Both connections still require_auth=scram-sha-256 and retain session guards and secret handling.

In the code: tools/provision_asset_scanner_role.py container environment filtering; tools/local_browser_assets.py prepare_document. Evidence is under local-evidence/local-browser/960cb250aae84bb99cfb974b9527b626 in provision-environment-probe.json and provision-profile-restore-probe.json.
