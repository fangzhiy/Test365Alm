# 交接：feat/audit-history（路线图阶段 2c 审计追踪查看）

## 目标

用例、运行、缺陷各有一个历史页，显示：谁、何时、做了什么、旧值 → 新值。只读功能，不改写入路径，不加迁移。

## 已完成（Claude，2026-11-10）

- 后端已提交（`d78d0b9`），新包 `apps/server/src/main/java/com/test365alm/server/history/`：
  - `HistoryController`：`GET /api/v1/projects/{projectId}/history/{objectType}/{objectId}?cursor=&limit=`，`objectType` 仅 `test_case | defect | run`，其他 400。
  - `HistoryService`：`commands.scope(actor,project,false,false)` 做权限（非成员 404），再查对象是否在该项目（不在 → 404）。**按 `(at,id)` 倒序（最新在前）** keyset 分页，游标是 base64url(`Instant|uuid`)，坏游标 400。limit 走 `CoreCommands.limit`（默认 25，1..100）。
    - 用例：`audit_event` → 对比 `test_revision` 第 n 版与 n-1 版（title/description/preconditions + 步骤）；只有 `test_case.created/revised` 出差异。
    - 缺陷：`audit_event` → 对比 `defect_revision`；只有 `defect.created/revised/transitioned` 出差异（`defect.retest-started` 的 object_revision 与上一条相同，不出差异）。
    - 运行：`execution_event WHERE run_id=?`，step 序号 join `run_step`，`revision` = attempt_no；动作名 `run.started / step.recorded / paused / resumed / run.finished / run.retried`。
  - `HistoryDiff`：纯函数。字段名约定：`title`、`status`…；步骤 `steps[n]`（整步增/删，值为 `动作 → 预期`）、`steps[n].action`、`steps[n].expected`、`steps[n].ordinal`（调序），运行另有 `steps[n].actualResult`、`steps[n].conclusion`、`status`、`conclusion`、`attempt`。空串当 null，首版空字段不输出。
  - 返回 JSON：`{items:[{id, at, actor:{id,displayName}, action, revision, changes:[{field,before,after}]}], nextCursor}`。
- 测试已通过：`HistoryDiffTest` 6 项；`HistoryDatabaseIT` 5 项（用例两版前后值、缺陷流转、运行全生命周期、非成员/跨项目/类型错配 404 + 坏类型/坏游标 400 + 撤权、7 条分 3 页不重不漏）。

## 下一步（按顺序，前端在 `apps/web/src/`）

1. `historyApi.ts`：仿 `defectsApi.ts` 的写法（`requestJson` + 运行时校验，坏数据抛 `{code:'INVALID_RESPONSE'}`）。导出类型 `HistoryObjectType='test_case'|'defect'|'run'`、`HistoryChange`、`HistoryEntry`，方法 `historyApi.list(projectId, objectType, objectId, cursor|null, {signal})`，URL 带 `limit=25`，cursor 用 `encodeURIComponent`。
2. `HistoryPanel.tsx`（props：`projectId, objectType, objectId`），`<section aria-label="审计历史">`：
   - 表格列：时间（`toLocaleString('zh-CN')`）/ 人（displayName，缺失显示 id）/ 动作（中文标签映射）/ 字段（中文标签：标题、描述、前置条件、严重程度、优先级、状态、说明、结论、尝试、步骤 n、步骤 n 动作/预期/序号/实际结果/结论）/ 旧值 → 新值（null 显示 `—`）。没有字段变化的条目也显示一行。
   - "加载更多历史"按钮用不透明 `nextCursor` 追加；空状态"暂无历史"；403/404 显示"无权查看或对象不存在"。
   - **不要用 `useCoreRequest`**：它遇到 404 会置 `denied`，嵌在父组件里会误触父级 `onDenied`。自己用 `AbortController` 管加载，props 变化或卸载时 abort。
3. 嵌入 `DefectDetail.tsx`：在"不可变状态/内容历史"附近加 `<HistoryPanel key={current.rowVersion} projectId={projectId} objectType="defect" objectId={id}/>`（key 让写入后自动重载）。
   - `DefectsPanel.test.tsx` 把 `./defectsApi` 整个 mock 了，嵌入后 HistoryPanel 会打真实 fetch：在该测试文件里加 `vi.mock('./historyApi', …)` 返回空页，确认原有用例不受影响。
4. 测试：`historyApi.test.ts`（坏数据被拒、cursor 进 URL、403 保留 status）、`HistoryPanel.test.tsx`（渲染前后值、加载更多追加且带 cursor、空状态、404 提示）。
5. 用例/运行：**最后再做**。`TestCasesPanel.tsx`、`TestExecutionPanel.tsx` 正被目录树 worker（`w2-folder-tree`）修改，每个文件只加 import + 一行 `<HistoryPanel …/>`；最好等目录树合并进 dev 后 rebase 再加。

## 怎么验证

```bash
export JAVA_HOME=D:/code/ProOrca/Wetest365ALM/.tools/jdk-21 PATH=/d/code/ProOrca/Wetest365ALM/.tools/jdk-21/bin:$PATH
cd apps/server && mvn -B -ntp test && mvn -B -ntp -Pintegration verify   # 集成需 Docker Desktop（本机已在跑）
# 只跑本功能：mvn -B -ntp -Pintegration verify -Dtest=HistoryDiffTest -Dit.test=HistoryDatabaseIT -Dsurefire.failIfNoSpecifiedTests=false
cd apps/web && npm ci && npm run lint && npm run test:run && npm run build
trail check
```

## 注意事项

- 不得改写既有迁移 V1—V21；本功能不需要迁移。
- 演示环境端口：前端 15173、后端 18083（5173/8080 被其他项目占用，别动）。
- 工作区里 `.claude/settings.json`、`.trail/hook.cjs` 的改动是 graft 钩子自动刷新的，与本功能无关，没有提交，别混进功能提交。
- 合并回 dev 前跑完整后端单元/集成和前端测试；合并后删除本文件，并更新 `docs/alm-replacement-roadmap.md` 第 7 节。
