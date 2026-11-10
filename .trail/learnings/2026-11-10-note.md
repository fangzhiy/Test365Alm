---
title: "审计历史接口最新在前，只对产生新修订的动作出旧值新值差异"
author: "周"
date: 2026-11-10
branch: feat/audit-history
cost: { minutes: 8, tokens: 142071 }
id: l-0b383489
scope: repo
status: active
src: agent
touches:
  - apps/server/src/main/java/com/test365alm/server/history/HistoryController.java
  - apps/server/src/main/java/com/test365alm/server/history/HistoryDiff.java
  - apps/server/src/main/java/com/test365alm/server/history/HistoryService.java
  - apps/server/src/test/java/com/test365alm/server/history/HistoryDatabaseIT.java
  - apps/server/src/test/java/com/test365alm/server/history/HistoryDiffTest.java
  - docs/handoff/feat-audit-history.md
---

## Decided
- 历史接口按 (时间, id) 倒序分页（最新在前），游标是不透明 base64url 字符串，前端只原样回传。
- 用例只对 created/revised、缺陷只对 created/revised/transitioned 计算差异；缺陷"发起复测"的审计记录沿用当前修订号，算差异会把上一次变化再显示一遍，所以不出差异。
- 运行历史以 execution_event 为准（payload 已带前后值），不用 audit_event（它的 object_revision 固定为 0）。

## Watch out
- 前端历史面板不要用 useCoreRequest：它遇到 404 会置 denied，嵌在缺陷详情里会误触父级 onDenied。
- DefectsPanel 的测试把 defectsApi 整个 mock 了，嵌入历史面板后还要 mock historyApi，否则会打真实 fetch。

In the code: apps/server/src/main/java/com/test365alm/server/history/HistoryService.java（TEST_REVISING / DEFECT_REVISING、encodeCursor/decodeCursor）
