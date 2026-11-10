---
title: Select populated reviewer controls by their accessible combobox name
author: "周"
date: 2026-11-10
branch: feat-business-fields
id: l-bb4d84f0
scope: repo
status: active
src: agent
touches:
  - docs/handoff/feat-business-fields.md
  - apps/web/e2e/project-access.spec.ts
  - apps/web/src/ReviewerPicker.tsx
---

## Decided
Use an exact accessible combobox role/name for the reviewer selection in real-browser tests. The nested HTML label's text includes option text in Playwright getByLabel, so exact label matching can disappear once candidate names load; its accessible combobox name stays the field label.

## Tried and ruled out
The real owned Keycloak run reached the reviewer search but timed out before creating the test case (two other flows passed). A separate real Edge minimal DOM reproduced exact getByLabel count 0, nonexact count 1, and exact getByRole combobox count 1. Do not increase timeouts, retry the unchanged test, or remove the name-selection and reload assertions.

## Watch out
Testing Library label matching passed, so component tests alone did not reveal this browser selector difference. Preserve the real browser failure report and use a new fixed source/run after changing the selector. This is a test-only correction: backend evidence remains tied to the unchanged business source.

In the code: apps/web/e2e/project-access.spec.ts reviewer creation and reload selectors; apps/web/src/ReviewerPicker.tsx implicit label around select. Failure source da9906c; corrected selector commit b5cc1b2.
