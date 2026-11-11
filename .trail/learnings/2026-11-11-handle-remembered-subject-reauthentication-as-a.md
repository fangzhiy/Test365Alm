---
title: Handle remembered-subject reauthentication as a password-only form
author: "周"
date: 2026-11-11
branch: feat-signature
id: l-4c38dae6
scope: repo
status: active
src: agent
touches:
  - apps/web/e2e/project-access.spec.ts
  - apps/web/e2e/signatureUi.ts
---

## Decided
A real Keycloak prompt=login reauthentication can remember the subject and show only Password plus Restart login. Wait for its visible password field, fill username only when the IdP actually shows it, and leave same-subject verification to the existing server-side issuer/subject check. To test a deliberately different identity, explicitly use the IdP Restart login control to reach the account entry form; never manufacture an application proof or send credentials to an application endpoint.

## Tried and ruled out
The authorized c549b25 real browser run f554465384d446d8be1c8b8206d3bb00 failed execution and deviation signing at the shared helper's line12 waiting for a visible username field. Retained error-context YAML shows Password, Restart login and Sign In, with no visible username textbox; both safe timelines have challenge200 and no sign POST. Offline reconstructed DOM fixtures reproduce the old username-only assumption for absent and hidden fields, and prove full-account, remembered-subject, wrong-password replacement and explicit switch behavior after repair. These fixtures are UI-driver regressions, not authentication acceptance.

## Watch out
The separate E flow failed before challenge at its review setup and has a different cause; do not conflate the three failures. Do not increase timeouts, sleep, retry, broaden ownership admission or drop wrong-password/other-subject assertions. Preserve the original2P3F report, exact resource cleanup and source; all five flows still need a newly authorized real run at the repaired source. Filter retained contexts to their actual YAML page snapshot, since the same document embeds test source containing future assertions that are not live DOM evidence.

In the code: e2e/signatureLoginUi.ts fills the real IdP form; signatureUi.ts retains the wrong-password rejection and exact signature/provider assertions. The E other-subject callback explicitly requests Restart login. src/signatureLoginUi.test.ts uses reconstructed DOM only and no real credentials or server proof.
