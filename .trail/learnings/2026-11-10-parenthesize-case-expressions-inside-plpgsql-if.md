---
title: Parenthesize CASE expressions inside PLpgSQL IF conditions
author: "周"
date: 2026-11-10
branch: feat-signature
id: l-a15d97cb
scope: repo
status: active
src: agent
touches:
  - apps/server/src/main/resources/db/migration/V26__simulated_signatures.sql
  - tools/linux_test_inside.py
---

## Decided
Parenthesize a CASE expression used in a PL/pgSQL IF condition. PostgreSQL parses function bodies separately from the outer CREATE FUNCTION statement; outer SQL parsing alone cannot validate the IF expression. Keep all signature meaning checks intact and fix the expression boundary with parentheses.

## Tried and ruled out
pglast 7.22 (PostgreSQL parser17.7), without any database connection, accepted all25 outer V26 statements but rejected app_signature_record_guard with a syntax error. Isolating all six functions identified only that guard; adding two parentheses made the full nine PL/pgSQL bodies parse. Removing just those parentheses reproduced the rejection and byte-for-byte original source. Do not weaken the condition or replace an immutable migration from an accepted baseline; V26 is new and unaccepted, V1 through V25 stay unchanged.

## Watch out
The preceding real scan run exported only IllegalStateException/PSQLException and then precisely cleaned its containers. Raw Maven logs/XML were never exported and are no longer available, so parser reproduction proves a source syntax defect without proving the complete original runtime exception chain. Preserve that gap and the original failed report. Fixed Flyway types, SQLSTATEs and bounded migration version/line classifications help future diagnosis without exporting messages, SQL, paths or credentials. Parser success does not validate catalog types, RLS, real scanner operation or OIDC.

In the code: apps/server/src/main/resources/db/migration/V26__simulated_signatures.sql app_signature_record_guard IF checks meaning against a parenthesized CASE. tools/linux_test_inside.py exports lossy diagnostic classifications only. Existing SignatureDatabaseIT/SignatureMigrationUpgradeIT and real OIDC identities remain required; no inventory, selector or retry change substitutes for the next explicitly authorized real run.
