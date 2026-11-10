---
title: Paused Docker scanners omit live port metadata and need guarded recovery
author: "周"
date: 2026-11-10
branch: feat/audit-history
id: l-62495712
scope: repo
status: active
src: agent
touches:
  - apps/server/src/test/java/com/test365alm/server/asset/AssetTestInfrastructure.java
---

## Decided
Validate scanner ownership, container ID, start time and configured bindings while paused, then strictly compare the original actual published endpoint after unpause, because Docker Desktop returns empty NetworkSettings.Ports for a paused container. Recover the same verified scanner if post-pause validation throws before the caller enters its try/finally.
## Tried and ruled out
The original pauseScanner failed with Owned scanner has no real published endpoint at its post-pause check and left clamd paused; a fresh owned probe reproduced running Ports populated and paused Ports empty. Existing real timeout/recovery test failed before the fix and passed afterward. Do not assume the port is lost after resume or unpause other workers' containers.
## Watch out
Unknown labels, replacement/restarted processes and changed configured bindings must refuse unpause. Actual published endpoints must still match after resume. Seven unit guards cover recovery and refusals; both real pause/recovery methods pass. The full Windows suite still fails at EICAR uploads: six Defender 1116 detections of Java temporary upload files occurred in the same run, with quarantine 1117 events. Do not disable Defender, add exclusions, weaken assertions or call this full green. Keep raw local event paths ignored.
In the code: apps/server/src/test/java/com/test365alm/server/asset/AssetTestInfrastructure.java and AssetTestInfrastructureTest.java. Ignored evidence: local-evidence/audit-history/scanner-repro.log, scanner-green.log, scanner-final.log, review-integration.log, defender-events.json.
