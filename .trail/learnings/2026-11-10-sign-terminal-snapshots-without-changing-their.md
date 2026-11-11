---
title: Sign terminal snapshots without changing their business versions
author: "周"
date: 2026-11-10
branch: feat-signature
id: l-a162c8a2
scope: repo
status: active
src: agent
touches:
  - ".github/workflows/ci.yml"
  - apps/server/src/test/java/com/test365alm/server/PrincipalUpgradeIT.java
  - apps/server/src/test/java/com/test365alm/server/asset/AssetDatabaseIT.java
  - apps/server/src/test/java/com/test365alm/server/asset/AssetMigrationUpgradeIT.java
  - apps/server/src/test/java/com/test365alm/server/asset/AssetProvisioningUpgradeIT.java
  - apps/server/src/test/java/com/test365alm/server/defect/DefectMigrationUpgradeIT.java
  - apps/server/src/test/java/com/test365alm/server/release/ReleaseCycleDatabaseIT.java
  - apps/server/src/test/java/com/test365alm/server/release/ReleaseMigrationUpgradeIT.java
  - apps/server/src/test/java/com/test365alm/server/requirement/RequirementMigrationUpgradeIT.java
  - apps/server/src/test/java/com/test365alm/server/testcase/TestCaseMigrationUpgradeIT.java
---

## Decided

Keep terminal business versions unchanged when adding a signature. A signature confirms the existing approved revision, finished attempt, or closed deviation using its exact strong version under the project transaction lock and target row lock. Store it in an independent immutable record with a unique target/snapshot key, then commit audit, Outbox and frozen command response atomically.

## Tried and ruled out

Incrementing a closed deviation's business version would break its exact revision/version foreign key and the revision lookup used by the existing service. Finished attempts are immutable too. Reusing the content version as a signature counter would either rewrite historical evidence or require weakening established terminal guards. New content uses a new revision and new review; reruns use a new attempt.

## Watch out

Step saving increments the attempt version even when the step has its own version. An upgrade fixture must finish with the actual returned attempt version. A prepared signing challenge must be rejected after content changes, and concurrent distinct challenges must produce only one signed record. The independent signature does not make the old strong version a mutable slot for another signature.

In the code: contracts/signatures.md; SignatureService project lock and target row locks; V26 unique signature target; SignatureMigrationUpgradeIT; SignatureDatabaseIT concurrency cases. Real database verification is still pending the coordinator's owned resource window.
