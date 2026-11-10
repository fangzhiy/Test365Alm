---
title: Legacy JSON null must remain equal to absent business fields
author: "周"
date: 2026-11-10
branch: feat-business-fields
id: l-eb82adba
scope: repo
status: active
src: agent
touches:
  - apps/server/src/main/java/com/test365alm/server/defect/DefectService.java
  - apps/server/src/main/java/com/test365alm/server/requirement/RequirementService.java
  - apps/server/src/main/java/com/test365alm/server/testcase/TestCaseService.java
  - apps/server/src/test/java/com/test365alm/server/businessfields/BusinessFieldsTest.java
  - apps/web/e2e/defects.spec.ts
  - apps/web/e2e/project-access.spec.ts
  - apps/web/src/HistoryPanel.test.tsx
  - apps/web/src/HistoryPanel.tsx
---

## Decided
Normalize JSON NullNode to Java null only in optional business-field response DTOs because Jackson 3 otherwise breaks equality of an old frozen idempotency response after serialization. Preserve null as null; empty and nonempty frozen snapshots retain their exact values.

## Tried and ruled out
Weakening the old-key equality assertions or changing historical null to an empty object would hide a compatibility defect. Production services must not detect old schemas as a fallback; historical upgrade tests instead register explicit test-only SQL implementations frozen from 61711294ff10e16b8df29418badaa8b455bc80ea before upgrading to latest.

## Watch out
JsonNode null prints like Java null, so record string output alone hides the difference. Use fixed UTC timestamps in round-trip tests so offset normalization does not obscure the null mismatch. Old-schema snapshots held in a JDBC transaction must commit before Flyway needs ALTER TABLE locks. Input explicit null rejection and omitted-field inheritance are separate from response compatibility.

In the code: BusinessFieldsTest.missingValueNullAndInvalidReviewerCannotSilentlyBecomeValid; DefectService.Revision; TestCaseService.RevisionView; RequirementService.RequirementView and RevisionView; LegacyContentServices; businessfields/legacy-content-fixtures.md. Real full run c50d90143f4f46b39f8e68ba4f1817e7 reproduced two old-key equality failures, while original-column snapshot assertions passed.
