---
title: Keep application chrome separate from report printing and count actual attempts
author: "周"
date: 2026-11-13
branch: codex-main
id: l-229deb60
scope: repo
status: active
src: agent
---

## Decided
Application chrome stays inside @media screen because the report portal must retain its reviewed print geometry. Overview counts latest instance outcomes separately from all historical Attempts because the old summary categories overlap.

## Tried and ruled out
Long visible report button names made grid rows excessively tall; keep the full aria-label and shorten visible text. Do not populate activity from the design mock because there is no authorized project-wide activity API. Default all-core Vitest imports failed with native memory allocation errors on this machine after adding UI dependencies; two workers ran the full suite without changing assertions, retries or timeouts.

## Watch out
Cross-API aggregation is not a transactional snapshot. Explicit synchronous caps must reject partial totals. Read all pages of each Run's Attempts because attemptNo alone misses multiple runs of the same instance. Cancel scope changes and ignore late history responses. Compare the same target PDF before/after and inspect every rendered page because text extraction alone cannot prove layout.

In the code: apps/web/src/Workspace.css, ProjectOverview.tsx, overviewData.ts, useRunHistory.ts; apps/web/vitest.config.ts. This change's OQD-01 PDF remained 5 pages with identical extracted text except generation time.
