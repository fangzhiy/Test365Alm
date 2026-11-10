---
title: Schema-only upgrades and cleanup must preserve independent authority boundaries
author: "周"
date: 2026-11-10
branch: feat-execution-grid
id: l-c560d69a
scope: repo
status: active
src: agent
touches:
  - tools/linux_test_runner.py
  - tools/tests/test_linux_test_runner.py
---

## Decided
Schema-only migrations may run before the operator creates runtime roles. Conditional role grants preserve that capability; migrations must neither invent a runtime LOGIN nor grant PUBLIC access. Keep the exact runtime permissions when the role exists.

A not-found cleanup result is acceptable only for a captured exact container ID after rechecking the same Docker context/Engine and independently confirming absence. All other errors and identity changes remain failures; never retry deletion.

## Tried and ruled out
Do not initialize roles in every old test fixture to hide a new migration dependency. Two real full-suite errors exposed V24's unconditional grants in no-role databases even though the role-enabled focused tests passed. Do not overwrite a failed run's cleanup=false with a later inspection result; retain both records.

## Watch out
A container can disappear between inspect, require and rm because Ryuk also cleans it. That window is deterministically reproduced offline, but the historical run's cleanup=false reason was not recorded and must not be retroactively declared proven. Export bounded cleanup stage/category events instead of raw Docker stderr. Once a first nonzero StartedAt is observed, preserve it as part of identity so a later restart cannot silently become the cleanup target. Exact not-found responses must name the requested full ID; substring matches, wrong IDs, extra diagnostic text and other exit codes are refused.

In the code: V24__execution_grid_and_step_evidence.sql follows the pg_roles guards already in V22/V23. PrincipalUpgradeIT and PlatformDatabaseIsolationIT preserve role absence before/after schema-only upgrades. Guard cleanup in tools/linux_test_runner.py records confirmed_absent_after_not_found or refused with a bounded stage/category. Run e7cd9b1f8ae64df2b7e6d18bf26086d8 retains original cleanup=false plus independent 53/53-absent verification.
