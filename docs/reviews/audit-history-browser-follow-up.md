# Audit browser acceptance and facilities follow-up (2026-11-11)

Supervisor confirmed A2 and advanced local dev to `1dd733036b4728216bbedef4d4350df5c0b7d292` (message `msg_7be1892d23d7`). This worktree does not move dev or merge it again. The archived feature handoff and coordinator review on dev remain authoritative.

## Verified business snapshots

- Combined backend source `34c19f615c8f80a4e28c64e233be613a3360b7d5`: 85 units and 167 integrations, all 0 failures/errors/skipped, phase exits 0, real ClamAV/CLEAN/EICAR preflight passed. Run `f9aa5b46eb0647908e62affaabeb5eb3`, cleanup true. Ignored evidence: `local-evidence/linux-tests/f9aa5b46eb0647908e62affaabeb5eb3/{run,scan,unit,integration}.json`.
- Real browser source `4a135897a964c92bdb4e2fe6e3dba29e738c0ff3`: three exact Keycloak flows passed, workers 1, retries 0, skips 0. Run `06caafafb42e4762b6f4b9f222265ab7`, exit 0, cleanup true, processes_stopped true. Ignored evidence: `local-evidence/local-browser/06caafafb42e4762b6f4b9f222265ab7/{results,run,manifest}.json`.
- Exact browser methods: `real Keycloak UI member creates defect from FAIL retests PASS closes and preserves history`; `real Keycloak UI manual execution creates, resumes, finishes and reruns a two-step run`; `real Keycloak UI manual test case flow creates steps, edits order, and keeps viewer read-only`.
- Post-run queries in the recorded local context found no run-labelled containers, networks or volumes. Generated snapshot environment, realm and secret Compose files were absent. Installed Edge used real OIDC/CSRF/PostgreSQL; browser-only assets were disabled, with real attachment validation separately covered above.
- Windows historical 1F/5E EICAR failures remain failed historical evidence; Linux demonstrates the real assertions pass without modifying Defender. Actual Node 26/npm 11.12.1 differs from target Node 24.

## Subsequent facilities-only changes

These changes follow the tested snapshots; they are not claimed as a new real browser/backend execution. Supervisor explicitly requested no rerun of those business flows.

- Reporter captures actual FullConfig workers and per-project retries. Two offline regressions prove differing values are reported and missing/mixed configurations rejected.
- Configuration and daemon preflight now execute inside unified finalization. Before a create attempt, refusal cleanup touches no Docker resources. Generated secrets are removed after daemon refusal or partial generation; existing environment/realm/Compose secrets are refused before generation and preserved. One parameterized main-level regression covers all five paths, alongside existing ownership/identity/daemon guards.
- Verification: browser guard group 7 tests; Python full suite 162 tests; frontend 25 files/250 tests, all passed. Lint exits 0 with existing 9 warnings. Production build and explicit Node-typed reporter TypeScript check pass. Trail build/check exit 0; check warns that the cumulative branch diff exceeds its 400 KB scan cap, so this is not exhaustive branch review. These are facilities checks, not additional business acceptance. Evidence logs remain under ignored `local-evidence/audit-history/`.

No push or PR. Pre-existing automatic hook/settings changes remain outside this facilities commit. Docker validation slot is released to the supervisor's next serial task.
