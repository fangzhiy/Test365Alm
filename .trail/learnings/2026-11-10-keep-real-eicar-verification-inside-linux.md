---
title: Keep real EICAR verification inside Linux to avoid Windows upload quarantine
author: "周"
date: 2026-11-10
branch: feat/audit-history
id: l-f8bd4ccb
scope: repo
status: active
src: agent
touches:
  - tools/linux_test_runner.py
  - tools/linux_test_inside.py
---

## Decided
Run the Maven process and upload temporary files inside a digest-pinned local Linux runner when verifying the real EICAR path, because Windows Defender quarantines Java temporary upload files before the application can send them to ClamAV. Keep source, target, temp and Maven cache off Windows bind mounts; export only test identities/counts/status and tool versions.
## Tried and ruled out
Fixing scanner pause recovery removed the timeout cascade but Windows still failed six EICAR-upload methods (1 failure / 5 errors). Defender events correlated with those Java temp files. The identical committed backend in Linux then passed the real clean/EICAR preflight and the complete 157 integration methods with zero failures/errors/skips. No Defender exclusions, changed production semantics, mocks, removed assertions or test retries were needed.
## Watch out
The Docker socket gives full daemon authority; provenance labels are operation checks, not isolation of that authority. Require explicit authorization, pin local daemon and resource identities, keep Ryuk enabled, and never delete by project prefix. The verified snapshot was ef27b7a (82 units / 157 integrations). Directory-tree composition changes the exact test inventory and needs its own verified source SHA/counts; this result does not satisfy the three real browser flows.
In the code: tools/linux_test_runner.py, tools/linux_test_inside.py, tools/linux-test/Dockerfile, and the LinuxRunLabels test-only SPI. Ignored evidence: local-evidence/linux-tests/e319bf4bb24440a5bb472ea6559700b6; run.json records zero exit and successful container cleanup, 33 recorded identities. The labelled runner image and build cache are intentionally retained; no shared cache pruning.
