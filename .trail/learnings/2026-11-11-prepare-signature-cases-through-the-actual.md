---
title: Prepare signature cases through the actual review action
author: "周"
date: 2026-11-11
branch: feat-signature
id: l-05fa42f3
scope: repo
status: active
src: agent
touches:
  - apps/web/e2e/project-access.spec.ts
---

## Decided
Prepare the signature's approved revision through the actual review region and its exact product action name, 批准本修订. Verify that setup against the real ReviewPanel rather than guessing a shorter label or widening the selector.

## Tried and ruled out
The c549b25 E browser flow timed out at project-access source line302 before any signature path. Its retained page snapshot was still 待复核; the fixture requested exact button 批准, while ReviewPanel renders 批准本修订. A shared setup helper preserved the old request and failed offline against the real ReviewPanel after SUBMIT; the exact current name passes and sends SUBMIT with version1 then APPROVE with version2 for the same immutable revision. The other two signing failures were independently at Keycloak's remembered-subject form.

## Watch out
Keep the approved-state assertion and all signature checks. Do not loosen matching, bypass review through API seeding, change business authorization or claim the reconstructed component fixture as real UI acceptance. The original2P3F remains, and the next authorized window must run all five flows.

In the code: e2e/signatureReviewUi.ts; project-access E callback; src/signatureReviewUi.test.tsx renders the product ReviewPanel with offline API fixtures and checks exact revision/version commands.
