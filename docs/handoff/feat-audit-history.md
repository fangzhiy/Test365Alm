# 交接：feat/audit-history（路线图阶段 2c 审计追踪查看）

## 目标

用例、运行、缺陷各有一个历史页，显示：谁、何时、做了什么、旧值 → 新值。只读功能，不改写入路径。

## 已完成

- 现状调研（结论也记在 `.trail/learnings/2026-11-10-audit-history-storage.md`）：
  - `audit_event`（V3 建表，V5 加 roles/authorization_version 列）字段：`id, tenant_id, project_id, actor_principal_id, action, object_type, object_id, object_revision, before_hash, after_hash, provenance, at`；只有索引 `(tenant_id, project_id, at, id)`；RLS `audit_event_select` 允许项目有效成员（含 viewer）读。
  - **只存哈希，不存字段值**，旧值/新值必须从别处还原：
    - 用例：`TestCaseService.appendAudit`（action `test_case.created` / `test_case.revised`，`object_revision` = 修订号）→ 相邻 `test_revision` + `test_step` 对比。
    - 缺陷：`DefectService.event`（`object_revision` = rowVersion）→ 相邻 `defect_revision`（title/description/severity/priority/status/reason）对比。
    - 运行：`ExecutionService.appendEvent` 同事务写 `execution_event` + outbox + audit（audit 的 `object_revision` 固定 0）→ 以 `execution_event` 为准；`STEP_RECORDED` payload 已含 `beforeActualResult/afterActualResult/beforeConclusion/afterConclusion`。
  - `principal` 无 RLS，runtime 可读 `display_name`。
- 计划已获用户确认（2026-11-10）。

## 下一步（按顺序）

### 后端（新包 `apps/server/src/main/java/com/test365alm/server/history/`，不加迁移）

1. `HistoryController`：`GET /api/projects/{projectId}/history/{objectType}/{objectId}?cursor=&limit=`，`objectType` 仅 `test_case | defect | run`，其他 400。路径风格参照 `DefectController`。
2. `HistoryService`：先走现有项目访问检查（参照 `DefectService.history` 的权限/项目解析方式）；非成员或对象不存在 → 404（不泄露存在性）。全部经受限 runtime 数据源 + RLS。
   - 返回 `CoreCommands.Page<HistoryEntry>`（keyset 游标，按 `(at, id)`）。
   - `HistoryEntry{at, actor{id, displayName}, action, revision, changes[{field, before, after}]}`。
   - 用例：`audit_event` ⋈ `test_revision`（按修订号），与上一版对比；步骤按 step_key 标增/删/改/调序。
   - 缺陷：`audit_event` ⋈ `defect_revision`（按 revision_no = object_revision），与上一版对比。
   - 运行：`execution_event WHERE run_id=?`，按 `occurred_at, id`；从 payload 展开前后值，暂停/继续/完成/重跑显示状态变化。
3. `HistoryDiff`：纯函数做字段对比，便于单测。

### 前端（`apps/web/src/`）

4. `historyApi.ts` + `HistoryPanel.tsx`（props：`projectId, objectType, objectId`）：时间线表格（时间 / 人 / 动作 / 字段 / 旧值 → 新值），"加载更多"用不透明 `nextCursor`，处理空状态与 403/404。
5. 缺陷：嵌入 `DefectDetail.tsx`。
6. 用例/运行：**最后再做**。`TestCasesPanel.tsx`、`TestExecutionPanel.tsx` 正被目录树 worker（`w2-folder-tree`）修改，每个文件只加 import + 一行 `<HistoryPanel …/>`；最好等目录树合并进 dev 后 rebase 再加。

### 测试

7. 单元：`HistoryDiffTest`（字段变化、步骤增删改调序、首版无旧值）。
8. 集成：`HistoryDatabaseIT`（Testcontainers）：用例创建→修订两条且前后值正确；缺陷流转状态前后值；运行记录步骤→完成；viewer 可读；非成员/跨项目 404；分页游标连续不重不漏。
9. 前端 vitest：`HistoryPanel.test.tsx`、`historyApi.test.ts`。

## 怎么验证

```bash
export JAVA_HOME=D:/code/ProOrca/Wetest365ALM/.tools/jdk-21 PATH=/d/code/ProOrca/Wetest365ALM/.tools/jdk-21/bin:$PATH
cd apps/server && mvn -B -ntp test && mvn -B -ntp -Pintegration verify   # 集成需 Docker Desktop
cd apps/web && npm ci && npm run lint && npm run test:run && npm run build
trail check
```

## 注意事项

- 不得改写既有迁移 V1—V21；本功能不需要迁移。
- 演示环境端口：前端 15173、后端 18083（5173/8080 被其他项目占用，别动）。
- 合并回 dev 后删除本文件，并更新 `docs/alm-replacement-roadmap.md` 第 7 节。
