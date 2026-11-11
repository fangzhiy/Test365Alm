---
title: Publish accepted results on main and integrate work on dev
author: "周"
date: 2026-11-11
branch: integration/audit
id: l-24ac8d65
scope: repo
status: active
src: agent
touches:
  - docs/coordinator-handoff.md
  - docs/oq-delivery-plan.md
---

## Decided

Use main for accepted delivery results and as the GitHub default branch, dev for integration, and feat/* for feature work. The user explicitly chose this conventional layout after seeing the old task-named default branch. A local rule that the main code folder stays on dev defines the integration workspace; it does not mean accepted releases should be published only to remote dev.

## Tried and ruled out

Publishing only dev leaves GitHub's landing page on an old task branch. Keeping chore/r01-status-001 as the default also obscures the role of the main line. Preserve that old branch and its history instead of deleting it or rewriting shared commits.

## Watch out

Confirm the live remote default and ancestry before publishing. Merge reviewed feature results through dev, then advance main to the same accepted commit without force pushes. Publishing source does not grant deployment authorization or permission to resume paused feature work. Later explicit user instructions take precedence.

In the code: docs/coordinator-handoff.md records the branch roles; docs/oq-delivery-plan.md records the active delivery scope. The repository is fangzhiy/Test365Alm.
