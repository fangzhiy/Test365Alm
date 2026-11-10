---
title: Select the owner combobox because its labelled form also matches
author: "周"
date: 2026-11-10
branch: feat-execution-grid
id: l-e9dbdc71
scope: repo
status: active
src: agent
touches:
  - apps/web/e2e/manual-execution.spec.ts
---

## Decided
Select the instance owner with the exact combobox role/name in browser tests. The labelled assignment form and its select both contain the owner phrase, so Playwright substring getByLabel matches both and selectOption fails strict mode immediately.

## Tried and ruled out
The owned run 5d2d3971 reached the enabled assignment form after the set-detail lock repair; no assignment save or run POST occurred. An offline Edge157 probe used DOM rendered by the current ExecutionGrid with synthetic loaded candidates: the old locator matched FORM (assignment form) and SELECT (owner control), count2, and actually threw strict-mode violation. The exact combobox/name matched1 and successfully selected the member. No HTTP requests or product resources were used. This is distinct from waiting for candidates or the prior pending-state defect.

## Watch out
Fix both assignment and later clearing selectors, retaining UI save/reload/frozen owner assertions. Component Testing Library exact label matching does not reproduce Playwright default substring matching. Keep the real failed run and wait for a fresh reviewed source/run before claiming business acceptance; no timeout or retry increase.

In the code: apps/web/e2e/manual-execution.spec.ts owner assignment and owner clearing. Bounded real run: local-evidence/local-browser/5d2d39717d3040289b2db29d8677fd2b/. Offline probe: local-evidence/execution-owner-locator/probe.json and rendered.html. This extends the existing exact combobox selection learning l-bb4d84f0 to a labelled ancestor form.
