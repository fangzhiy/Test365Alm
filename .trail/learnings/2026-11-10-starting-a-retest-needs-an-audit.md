---
title: Starting a retest needs an audit refresh even when the defect version stays unchanged
author: "周"
date: 2026-11-10
branch: feat/audit-history
id: l-d66e0bdd
scope: repo
status: active
src: agent
touches:
  - apps/server/src/test/java/com/test365alm/server/asset/AssetTestInfrastructure.java
  - apps/web/src/DefectDetail.tsx
  - apps/web/src/DefectsPanel.test.tsx
---

## Decided
Refresh defect audit history after confirmed writes and successful explicit detail reads, because starting a retest adds an audit event without changing defect rowVersion. Include a local refresh counter alongside rowVersion in the history panel key.
## Tried and ruled out
Using rowVersion alone fails with stale audit history after starting a retest. The new regression observed only one history API call instead of two before the fix.
## Watch out
Never use draft edits as refresh triggers or replay a confirmed write when a history read fails. Keep history authorization errors local to the panel.
In the code: apps/web/src/DefectDetail.tsx and DefectsPanel.test.tsx. Ignored evidence: local-evidence/audit-history/retest-red.log and review-web-final.log.
