---
title: Check signing locks before directory callbacks clear execution state
author: "周"
date: 2026-11-10
branch: feat-signature
id: l-e149d4c2
scope: repo
status: active
src: agent
touches:
  - apps/web/src/TestExecutionPanel.tsx
---

## Decided

Guard same-scope directory completion before resetting execution selection or clearing its signature lock. The execution parent's folder move and selection callbacks share one synchronous guard that consults the current scope-stamped signature lock, then reset and refresh only when no signing intent is protected. Real project, identity, permission and reset teardown still clear protected state without using this directory-navigation guard.

## Tried and ruled out

Guarding loadSets was too late: the old folder callback called reset first, which cleared the lock and unmounted the SignatureSession, so the later guard saw no pending command. Disabling FolderTree after signing starts cannot stop a move already awaiting its placement response. A coordinator review found this gap after the child and earlier parent tests passed; the actual parent reproduced eight failures across challenge/sign, in-flight/unknown and move/select callbacks.

## Watch out

Capture the parent's callback before starting the signature to model the old closure held across the child's await. After invoking it, prove the selected attempt survives and retry reuses the original command, ETag and key. Test real scope teardown separately so the navigation guard cannot retain inaccessible content. The deviation parent has no directory reset path; a late create callback updates only its list, and four actual parent tests preserve the selected signed deviation. In synchronous component tests, wait for the reauthentication button to be enabled before firing a click and posting the popup result; the UI can render the button before its pending request finishes.

In the code: TestExecutionPanel.refreshFolder and FolderTree callback props; SignatureParents.intent.test.tsx (44 parent cases); local-evidence/signature/execution-folder-lock-red-corrected.log records 8 failures/36 passes before the fix, with the original command/version/key assertions green after repair. Backend inventory102/218/82, OIDC/API business rules, selectors, retries and skips are unchanged. Playwright --list discovers16 tests across7 files, which is discovery only; the real local window still requires exactly the original four plus E's fifth flow.
