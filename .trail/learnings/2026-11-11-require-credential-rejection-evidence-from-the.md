---
title: Require credential rejection evidence from the actual Keycloak field error layout
author: "周"
date: 2026-11-11
branch: feat-signature
id: l-01f60bc1
scope: repo
status: active
src: agent
touches:
  - apps/web/e2e/signatureLoginUi.ts
  - apps/web/e2e/signatureUi.ts
---

## Decided
Treat a rejected credential as a visible, known Keycloak field-error node in a polite live region, with the exact invalid-credentials message. The tested image ships both base and v2 layouts: base puts aria-live on the error span; v2 wraps the named field error in an ancestor live region. Require the specific error IDs and bounded message, then retain the separate no-signing-confirmation assertion. A password form or an arbitrary occurrence of the same text is insufficient evidence.

## Tried and ruled out
The real five-flow run at 7881f30 passed four flows but its E negative stopped at the old #input-error assertion. Its retained accessibility snapshot contains Invalid password.; it cannot establish DOM IDs. Read-only extraction of the exact existing Keycloak image confirms the template differences without starting a container. An initial base-only locator passed base fixtures but two new v2 fixtures failed, so the final locator covers both source-confirmed layouts and whitespace around the exact message. Increasing timeouts, removing the negative assertion, and treating later signing success as rejection proof were ruled out.

## Watch out
A snapshot's accessible text is not historical ID evidence. Keep the original failed run and distinguish reconstructed template tests from real authentication. The new locator still needs the next coordinator-authorized complete five-flow run, including wrong password, same subject, other subject, and immutable revision assertions. No product authentication, provider, callback, or business authorization changes are justified by this test-driver defect.

In the code: apps/web/e2e/signatureLoginUi.ts signatureCredentialRejection; apps/web/e2e/signatureUi.ts confirmSimulatedSignature; apps/web/src/signatureRejectionUi.test.ts. Exact image c6459d5fae1b759f5d667ebdc6237ab3121379c3494e213898569014ede1846d; retained local theme reports and failed run dae446c672c2418d860ba1959a5db57c are ignored evidence, not committed secrets. The base-only RED was 4F/6P; the v2 RED was 2F/12P before the final correction.
