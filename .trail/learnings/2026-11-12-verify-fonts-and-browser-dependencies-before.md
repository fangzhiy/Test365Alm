---
title: Verify fonts and browser dependencies before claiming the CI environment recovered
author: "周"
date: 2026-11-12
branch: codex-main
id: l-240b362b
scope: repo
status: active
src: agent
touches:
  - ".github/workflows/ci.yml"
  - tools/tests/test_ci_workflow.py
---

## Decided
Pin only the browser job to Ubuntu 24.04 and verify the complete preparation chain. Obtain Noto SC Regular and Bold from the official Sans2.004 immutable commit, verify SHA256 and OFL before installing, verify actual fontconfig files and Chinese coverage, then use Chromium CDP to prove both faces rendered into a real PDF. Keep the locked Playwright formal --with-deps installation and require its dry-run dependency check afterwards.

## Tried and ruled out
Both fed6024 CI runs and their single bounded reruns failed at Ubuntu mirror DNS/apt100 before browser tests. Do not rerun unchanged heads or call the missing reports application failures. Playwright 1.63.0 supports both Ubuntu24.04 and26.04, so unsupported26.04 is not the demonstrated cause. Pinning a runner is not proof of a DNS fix; the new full CI must actually run.

## Watch out
Font download success or fc-match output alone is insufficient. Fail on hash, family, weight, coverage, file selection, dependency or launch errors; preserve NOT_RUN and preparation evidence. Do not drop --with-deps, gates, cleanup, or use old PDFs as final-head evidence.

In the code: .github/workflows/ci.yml; tools/prepare_ci_fonts.py; tools/verify_ci_browser.cjs. Font commit523d033d6cb47f4a80c58a35753646f5c3608a78, Playwright1.63.0. No caching or download fallback.
