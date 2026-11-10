---
title: Windows demo startup needs LF initialization and local login cookies
author: "周"
date: 2026-11-10
branch: w1-demo-env
id: l-b12433fe
scope: repo
status: active
src: agent
touches:
  - README.md
---

## Decided
The local demo uses its own credentials, realm and persistent Keycloak data, then seeds business objects through OIDC/CSRF HTTP APIs. Windows database bootstrap mounts a demo-only LF copy of the existing shell initializer. The Python login client accepts Keycloak secure cookies over HTTP only for the exact configured 127.0.0.1 Keycloak origin, matching the browser's loopback behavior.

A durable completion checkpoint makes subsequent startups read-only for business objects. Partial seeding discovers saved records before retrying; successful idempotency responses expire after 24 hours, so those keys alone cannot prevent duplicates indefinitely. A completed fixture whose run states have since changed reports a verification mismatch instead of overwriting the demonstrator's new attempts.

## Tried and ruled out
Mounting the Windows checkout's initializer directly failed with exit 127 because its CRLF shebang resolved to a nonexistent interpreter. Changing the shared script or existing R03 environment was unnecessary; a generated LF copy fixed the fresh demo.

The default urllib CookieJar omitted Keycloak's Secure AUTH_SESSION_ID and KC_RESTART cookies on local HTTP. Login POST returned 400 with Restart login cookie not found although the account was valid. Do not disable cookie security globally or enable password grants to bypass OIDC.

The test-case list contains summaries, not full revisions. Treating those rows as details worked on the empty first seed but failed the second startup. Offline repeatability fixtures now use the real summary shape, and the seeder fetches each selected case's detail.

## Watch out
The demo machine has long-lived unrelated apps on 5173 and 8080; the authorized demo defaults are 15173 and 18083. Port conflicts must refuse startup, never stop their owners. Existing demo credentials and callback ports are preserved. PostgreSQL bootstrap SQL is limited to tenant/domain/tenant-member scope; projects, revisions, manifests, execution and defects must remain on the business APIs.

In the code:
- tools/demo_up.py, tools/demo_seed.py, tools/demo_down.py, tools/tests/test_demo.py
- python tools/demo_up.py; python tools/demo_down.py; explicit destructive option: --delete-data
- Ignored files: .env.demo and local-evidence/demo/; fixed Compose project: test365alm-demo
