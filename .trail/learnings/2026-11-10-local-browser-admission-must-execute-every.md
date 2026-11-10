---
title: Local browser admission must execute every required flow and capture full identities before start
author: "周"
date: 2026-11-10
branch: feat-review
id: l-4474239b
scope: repo
status: active
src: agent
touches:
  - apps/web/e2e/project-access.spec.ts
  - tools/local_browser_runner.py
  - tools/tests/test_browser_resource_guard.py
  - tools/tests/test_local_browser_assets.py
---

## Decided
Every local acceptance flow must use the existing isOwnedRun admission, which accepts validated local manifests or the existing CI identity. Verify the actual registered review callback offline: valid local and CI scopes reach its first UI operation; unowned or mismatched scopes stop before it. Keep the four-result guard strict even when Playwright's overall status says passed.

## Tried and ruled out
The first D browser run had three passes and one expected skip because its callback still used isOwnedCiRun. The aggregate passed status did not mean D ran; the four-identity guard correctly returned exit1. Removing ownership checks or treating an expected skip as success would hide the failure.

## Watch out
Capture ownership-start after create and before start: full container IDs, Created/Image, full network ID with its own properties, exact volume names and run/source/project labels only. Docker listing prefixes cannot be upgraded to full historical evidence after cleanup. Preserve the original run's 12-character network limitation.

In the code: project-access review callback; reviewBrowserOwnership.test.ts; local_browser_runner.ownership_start. Failed real source6fa15ce/run1ab1d837 has retained 3P1skip evidence; the new source still needs a fresh authorized four-flow execution.
