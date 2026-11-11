---
title: Use one Codex checkout and direct tested integration
author: "周"
date: 2026-11-11
branch: codex-main
id: l-20461b1c
scope: repo
status: active
replaces: l-8cd67a73
src: agent
---

## Decided

The user explicitly replaced coordinator/worker/independent-reviewer development with one Codex working directly in codex-main, created from dev. Keep only that checkout and the main folder on dev. Finish a feature by running backend unit tests, full integration inside Linux, frontend lint/test/build and Trail check, then integrate dev and advance accepted results to main for push.

The change removes repeated orchestration, manual test-name audits, exclusive-window approvals and independent cleanup-report paperwork. Existing automated tests and gates still run. Keep one short docs/reviews record per feature and save durable reasoning as learnings.

## Tried and ruled out

Creating a fresh worktree and separate reviewer for each feature accumulated redundant checkouts and repeated the same validation narrative. Do not recreate that topology or revive the old per-window approval protocol from historical handoffs. Preserve history and evidence instead: old branches remain, and old local-evidence is archived outside the repo.

## Watch out

Do not delete tests, add retries, weaken permissions/RLS/immutable history/audit transactions, publish secrets, change Defender, or touch other projects, test365alm-r02-postgres-1 or ports 5173/8080. Simplifying paperwork does not weaken these constraints. F reporting and G/A03 stay paused until the user explicitly resumes them.

In the code: AGENTS.md and docs/coordinator-handoff.md hold the active process; docs/handover/resume-guide.md replaces the worker runbook, and docs/handover/evidence-index.md maps the external evidence-archive. This replaces the old per-feature worktree and Claude/Codex handoff convention.
