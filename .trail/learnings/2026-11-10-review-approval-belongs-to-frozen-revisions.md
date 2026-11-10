---
title: Review approval belongs to frozen revisions and UUID identity values
author: "周"
date: 2026-11-10
branch: feat-review
id: l-c9455188
scope: repo
status: active
src: agent
touches:
  - contracts/test-case-review.md
---

## Decided
Keep each review decision as an immutable event on one sealed content revision. Derive the current revision status from that revision's events, because carrying an entity-level approval onto a new revision would silently approve changed content. Compare designated identities as UUID values while hashing the original frozen business-field representation: the business-field contract already accepts canonical UUID text in either letter case.

## Tried and ruled out
A mutable current approval flag would erase or reinterpret earlier decisions. Comparing the frozen reviewer text directly with UUID::text rejects valid uppercase identities because PostgreSQL formats UUID output in lowercase; rewriting the frozen field to avoid that mismatch would change the reviewed content.

## Watch out
Review writes share the test case's rowVersion with content edits; revisionNo is not a concurrency token. Replays must reauthorize both the command actor and the exact historical designated reviewer before returning the frozen response. An unknown browser write result keeps its original command, version and key, and locks related content writes until the outcome is confirmed.

In the code: contracts/test-case-review.md; V25__test_case_revision_review.sql; TestReviewService.act; ReviewPanel.tsx; TestCasesPanel.tsx. The uppercase UUID regression is compiled but not yet executed against PostgreSQL at this checkpoint. Real integration and browser validation still await the coordinator-approved C baseline and resource slot.
