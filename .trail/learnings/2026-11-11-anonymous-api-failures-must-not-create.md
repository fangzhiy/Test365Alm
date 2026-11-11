---
title: Anonymous API failures must not create competing login sessions
author: "周"
date: 2026-11-11
branch: fix/auth-fix-01
id: l-60661da0
scope: repo
status: active
src: agent
touches:
  - apps/server/src/main/java/com/test365alm/server/identity/IdentitySecurity.java
  - apps/server/src/test/java/com/test365alm/server/identity/OidcCallbackSecurityIT.java
  - apps/web/e2e/assets.spec.ts
  - apps/web/e2e/project-access.spec.ts
  - apps/web/e2e/safeHttpDiagnostics.ts
  - apps/web/src/browserDiagnostics.test.ts
  - tools/linux-test/combined-methods.json
  - tools/linux_test_inside.py
  - tools/tests/test_execution_focus.py
  - tools/tests/test_linux_test_runner.py
---

## Decided
Anonymous SPA API authentication failures must not create a saved-request session. Use NullRequestCache while retaining Spring's separate OAuth2 authorization-request session repository. The SPA uses a fixed login success URL, so it has no saved-request continuation to restore.
## Tried and ruled out
At unchanged product baseline b98ea8d (same product code as cc548678), a real HTTP regression withheld anonymous /me response headers until after the authorization redirect and then delivered them to the same CookieManager. The late JSESSIONID replaced the session containing the authorization request: callback login=failed, no token exchange. With NullRequestCache the identical sequence completes token exchange and /me200 without an anonymous Set-Cookie. Do not serialize the UI login link, retry, sleep or relax OAuth validation to mask this server-side race.
## Watch out
The archived main42938066191 isolated timeline has authorization302 followed 2ms later by initial /me401, then callback302 and /me401; dev42938066162 at the same SHA passes. This is consistent with the reproduced race but those historical artifacts lack cookie/session/failure classification, so causation of each historical failure is not proven. Keep random diagnostic IDs independent of session/state/nonce; only whitelist categories and stages, never exception messages or credentials.
In the code: IdentitySecurity.configureCommon; LoginDiagnostics; OidcCallbackSecurityIT.lateAnonymousMeResponseCannotReplaceAuthorizationSession. RED command: mvn -B -ntp -Pintegration verify -Dit.test=OidcCallbackSecurityIT#lateAnonymousMeResponseCannotReplaceAuthorizationSession (1 failure, zero errors/skips). GREEN: same regression plus legalTokenCompletesHttpCallbackAndPersistsPrincipal, failedCallbackDoesNotPoisonClientAndFreshLegalAuthorizationRecovers and wrongNonceIsRejectedAfterRealTokenExchangeWithoutPrincipalWrite (4 passed).
