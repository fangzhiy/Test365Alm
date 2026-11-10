---
title: Completed demos stay read-only when folder seeding is added
author: "周"
date: 2026-11-10
branch: r2-merge-w2
id: l-6b604cc7
scope: repo
status: active
src: agent
touches:
  - README.md
  - tools/demo_seed.py
  - tools/tests/test_demo.py
---

## Decided
Directory initialization belongs to fresh or interrupted demo seeding; an existing completion checkpoint remains read-only. A review must not silently reorganize an already running demonstration when restarting it, because the demonstrator may have deliberately moved objects since initial creation.

## Tried and ruled out
Automatically upgrading completed demos to the new folder layout was rejected: it would turn the established read-only restart into business writes and could override user organization. Fresh seeds discover durable folder identities rather than relying on expiring idempotency keys alone.

## Watch out
An interrupted seed may move an object from virtual root or accept an already matching placement, but refuses an existing different folder. Applying the layout to a completed running demo needs a separately authorized, explicit operation; do not delete its checkpoint just to force this.

In the code:
- tools/demo_seed.py: demo_folder, place_demo_object, seed completion checkpoint
- tools/tests/test_demo.py: DemoSeedTests
