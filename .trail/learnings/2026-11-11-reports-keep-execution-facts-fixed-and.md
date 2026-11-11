---
title: Reports keep execution facts fixed and classify the latest instance attempt before filtering
author: "周"
date: 2026-11-11
branch: codex-main
id: l-6cf2f932
scope: repo
status: active
src: agent
touches:
  - apps/web/src/Reports.tsx
  - apps/web/src/reports.css
---

## Decided
Reports select one latest-started Attempt per instance before applying status filters. Break timestamp ties by run UUID then Attempt UUID descending, matching the execution grid. Classifications are mutually exclusive; old active attempts are counted separately. Completed includes PASS, FAIL and BLOCKED; zero denominators are unknown rather than 100%.

Report facts read the exact manifest source revision and requested Attempt. Saved signature payloads are evidence for their original object/snapshot only; current wrapper versions and dynamically joined deviations are not signed report content. One repeatable-read read-only transaction authorizes current project access before querying under RLS.

## Tried and ruled out
The existing execution summary's active and latest-completed counts overlap, so summing them would misclassify instances. Looking up a current test revision or regenerating a signature snapshot would silently rewrite historical meaning. Returning the first page would make a plausible but incomplete printout.

## Watch out
Project names, current identity names and deviation states are generated-time supplements, not frozen historical labels. Preserve original FAIL evidence and distinguish retest resolvedRevisionId cycles. Report size caps reject the entire response; attachment metadata must exclude storage keys. Chinese PDF validation must inspect actual rendered pages, including the last page: the app root background can otherwise print beneath the report.

In the code: contracts/reports.md; ReportService; ReportRules; reportsApi.ts; Reports.tsx; reports.css. Test with ReportDatabaseIT and e2e/reports.spec.ts. This is technical report capability, not business format approval or G/A03 acceptance.
