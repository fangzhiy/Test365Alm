# 交接：feat/audit-history（路线图阶段 2c 审计追踪查看）

## Codex 接力更新（2026-11-10，优先于下面的原交接）

### 第二次接力：三个历史入口均已接入

- 用户要求“继续”后，在当前分支补齐用例与运行入口（目录树尚未合入 dev；两个产品文件均只加 import 与一行组件，未合入其他功能分支）。
- TestCasesPanel 用稳定 test id / rowVersion 刷新历史；仅在详情加载成功后显示。
- TestExecutionPanel 使用 runId 查询历史，刷新 key 包含 attempt id、attempt rowVersion 和每个步骤的 rowVersion；步骤独立写入不一定改变 attempt rowVersion，不能只监听 attempt 版本。草稿文字不进入刷新 key。
- 新增四项组件回归：用例保存后重载、用例 VIEWER 历史 404 保留详情、运行步骤/暂停/继续/完成/重跑均重载、运行 VIEWER 历史 403 保留详情。既有五处状态断言改为按具体业务消息查找，避免与新增历史面板的 status 混淆。
- 已给现有用例、运行、缺陷的真实 Keycloak 浏览器流程增加审计差异与事件断言，保留原测试名、单 worker、零 retry。Playwright 发现 14 个原有测试，三个修改文件的独立 TypeScript 检查通过；本机没有 CI-owned 资源，真实流程 NOT_RUN，没有伪造归属或绕过保护。
- 第二次接力验证：前端全套 21 文件、234 项全部通过；lint / build 通过，原有 9 个 warning 未增加。后端代码没有变化，沿用下方已记录的 72 单元通过、完整集成失败结果，未重复运行。
- Playwright CLI 使用合成夹具实际点击用例保存、打开运行、暂停、继续，观察两个历史面板自动更新，控制台零错误/警告。夹具在 apps/web/local-evidence/history-entries/，截图和诊断在根目录 local-evidence/audit-history/，均忽略。测试浏览器和经进程路径确认归属的 15174 预览服务已关闭。
- 下一步为 Claude 审查、与已审核目录树分支集成、CI-owned 真实浏览器验收及资产集成失败的独立排查；三个历史入口本身不再待开发。尚未推送/合并，不标记阶段 2c 已验收完成。

### 已完成

- 原“下一步”1—4 已完成：`historyApi.ts` 复用 requestJson 并验证页/事件/主体/时间/修订号/字段值，保留 HTTP 错误 status，固定 limit=25，编码路径与不透明 cursor。
- `HistoryPanel.tsx` / `HistoryPanel.css`：中文动作/字段标签、时间、主体名或 ID、前后值、最新在前、追加分页、空状态、错误与重试、表格局部横向滚动。无字段变化的事件仍显示。
- 面板独立处理请求；403/404 只在面板提示并清空旧数据，401 提示重新登录。分页失败保留原页和游标。切换对象、卸载、StrictMode 重放均取消请求；迟到响应不会覆盖当前对象。
- DefectDetail 在不可变修订历史附近接入，key 使用 current.rowVersion，保存新修订后重载。DefectsPanel 测试已 mock historyApi，增加历史 404 不关闭详情及保存后重载的回归。
- 新增 historyApi 测试 8 项、HistoryPanel 测试 8 项，以及上述缺陷入口回归。

### 验证

- 通过：npm ci；前端全套 21 文件、230 项，零失败/跳过；npm run build。
- 通过：npm run lint 退出 0；其他文件保留原有 9 个 warning，本次新增文件无 warning。
- 通过：Java 21 下后端单元 72 项，零失败/错误/跳过。
- 未通过：Java 21 下完整 `mvn -B -ntp -Pintegration verify`，耗时 8:41，157 项、2 failures / 21 errors / 0 skipped。HistoryDatabaseIT 本轮 22:25 的 5 项全部通过。资产四个测试类共 1 failure / 19 errors（扫描器不可用、容器发布端点检查失败、存储不可用等）；OidcCallbackSecurityIT 的三个资产 HTTP 方法共 1 failure / 2 errors（terminal complete 预期 200 实际 503；typed links 与 integrity 方法请求超时）。其余测试类通过。本轮只改前端，未修改这些后端测试/服务；失败根因尚未证明，不能宣称已修复或可合并。
- Playwright CLI 组件检查通过：独立端口 15174，合成数据验证 320/768/1024/1440 宽度、长文本、追加分页和可访问表格结构，重载后控制台零错误/警告。不是实际 OIDC/业务端到端验收。浏览器和临时 Vite 已关闭，没有修改原演示服务。
- trail check 退出 0，无冲突；仅有分支已有文档与学习的关键词提醒。
- 实际环境 Java 21.0.13、Node 26.0.0、npm 11.12.1；Node 与目标 24 LTS 存在差异。
- 忽略证据在根目录 local-evidence/audit-history/（日志）和 apps/web/local-evidence/history-preview/（合成夹具/截图），不提交。

### 接下来

1. Claude 审查本次前端改动。
2. 两个入口与组件测试在第二次接力中完成；目录树分支合并到 dev 后再集成最新 dev，保留两边的面板接入。
3. 在 CI-owned 环境实际运行已补审计断言的三条真实登录流程。本机未运行，不以合成夹具检查代替。
4. 全套后端集成绿色前不可合并。合并后再删除本文件、更新路线图第 7 节；当前不标记整个 2c 完成。未推送、未合并。

beforeEach 初始化 mock 使用花括号，不隐式返回 mock 函数（Vitest 会把函数返回值当清理函数调用）。原有两个自动刷新钩子文件的改动继续保留，不混入功能提交。

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
