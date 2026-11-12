---
title: Inspect completed demos as actual state and resume only explicit versioned scenarios
author: "周"
date: 2026-11-11
branch: codex-main
id: l-c2151c43
scope: repo
status: active
src: agent
touches:
  - ".github/workflows/ci.yml"
  - AGENTS.md
  - README.md
  - apps/web/src/DefectsPanel.test.tsx
  - apps/web/src/ExecutionGrid.tsx
  - docs/alm-replacement-roadmap.md
  - docs/coordinator-handoff.md
  - docs/oq-delivery-plan.md
  - tasks/oq-delivery-todo.md
  - tools/demo_down.py
---

## Decided
Completed demo startup is read-only inspection of recorded identities and current server state, not enforcement of a permanently frozen PASS/FAIL distribution. Initial seeding still checks its exact expected distribution. Only explicit resume advances a versioned synthetic scenario. This follows the user's OQ-DEMO-01 authorization; legacy projects and data volumes must survive unchanged.

## Tried and ruled out
Requiring 11 PASS, 2 FAIL and 1 RUNNING forever rejected legitimate new attempts. Editing previously approved/executed records to retrofit a rejected-review demonstration was also rejected: preserve the interrupted v1 prototype and create a distinct v1.1 project. Real UI execution must await the exact Run/Attempt/read/write response; a PASS banner from the previous selection is insufficient evidence.

## Watch out
Use the real revisionNo field, typed runStepId attachment filter and occurrence_department/location business keys. A later version or manual ownership mismatch must stop rather than overwrite. Signing uses the existing Keycloak popup and explicit confirmation, never SQL/Proof fixture creation. The separate persistent profile requires fresh SCRAM initialization and the official independent scanner LOGIN provisioning tool; do not retrofit old HBA or credentials.

In the code: tools/demo_seed.py, tools/demo_up.py, tools/oq_demo.py, apps/web/e2e/oq-demo.spec.ts and tools/scenarios/oq-demo-01-v1.1.json.
