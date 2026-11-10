---
title: Unknown review retries must survive parent navigation until scope teardown
author: "周"
date: 2026-11-10
branch: feat-review
id: l-2c2b1181
scope: repo
status: active
src: agent
touches:
  - apps/web/src/TestCasesPanel.tsx
---

## Decided
An unknown review result must keep its ReviewSession mounted within the same project, identity, permissions and session generation. Guard both parent read entry points before canceling requests or clearing selected/detailReady, and disable the matching navigation controls and query form submission. A synchronous scope-stamped lock ref covers late directory callbacks as well as rendered button state.

## Tried and ruled out
ReviewPanel-only retry tests missed the parent: selecting the same or another case and submitting search with Enter unloaded the session, whose unmount callback unlocked the parent while its command, ETag and key disappeared. A disabled query button does not guard native form submission. Replacing the lost intent with a new key would create a different business command; adding a persistence framework was unnecessary.

## Watch out
Do not let the navigation lock block genuine scope teardown. A new identity with identical permissions must still reload, so scope effects use the complete access key. Project/permission/session reset must clear protected state, abort the old review, and ignore late success or failure. Read-only history can remain available because it does not replace the current review session. A directory move started before review can finish later, so its refresh callback must pass the same parent guard.

In the code: TestCasesPanel.load, selectTest, reviewPendingInScope, lockReview and the search/list/folder/conflict controls; TestCasesPanel.reviewIntent.test.tsx exercises the actual parent, ReviewPanel and FolderTree together. Parent regression was RED (19 failures, 13 passes); the final 41-case regression and full329 frontend tests passed offline. Backend95/202/66 and four real UI identities are unchanged; actual resource verification still awaits the coordinator.
