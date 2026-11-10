---
title: "审计事件表只存哈希不存旧值新值，历史页要从不可变修订和执行事件还原"
author: "周"
date: 2026-11-10
branch: dev
cost: { minutes: 2, tokens: 58855 }
id: l-0b383489
scope: repo
status: active
src: agent
---

## Decided
- 审计追踪查看（路线图 2c）的旧值/新值不从 audit_event 取：用例对比相邻 test_revision + test_step，缺陷对比相邻 defect_revision，运行以 execution_event.payload 为准。读取侧不加迁移。

## Watch out
- audit_event 只有 action/object_type/object_id/object_revision 和 before_hash/after_hash，没有字段值；运行类事件 object_revision 固定为 0，step 事件的 object_id 是 runId。
- execution_event 与 audit_event 在同一事务写入；STEP_RECORDED 的 payload 已带 beforeActualResult/afterActualResult/beforeConclusion/afterConclusion。
- principal 表没开 RLS，runtime 可读 display_name 显示“谁”。audit_event 读取由 RLS audit_event_select 限定为项目有效成员（含 viewer）。

In the code:
- 建表 V3__tenant_project_authorization.sql:100，V5 加 roles/authorization_version 列，索引只有 (tenant_id, project_id, at, id)
- 写入点：TestCaseService.appendAudit、DefectService.event、ExecutionService.appendEvent、CoreCommands（字段/追踪）
