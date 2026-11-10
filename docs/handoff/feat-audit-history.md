# 交接：feat/audit-history（路线图阶段 2c 审计追踪查看）

## 第三次接力：审查与失败定位（2026-11-11，优先于下文）

当前总管已改为外层 Codex，稳定入口为主工作区 `docs/coordinator-handoff.md`；原 Claude 不再负责最终决定。起始提交 `a797f3f`。本轮独立提交：

- `814d5db`：修复缺陷发起复测／同版本显式刷新后审计面板不刷新。复测只增加事件，不改变缺陷 rowVersion；增加本地刷新计数，成功写入或成功详情 GET 后刷新。新增回归先红后绿，不重发已确认命令。
- `4764d63`：仅附件测试设施兼容修复和七项守卫测试，与业务修复分开提交。

### 审查结论

已核对历史还原使用不可变用例／缺陷修订与运行事件 payload；查询先授权、再检查对象 tenant/project 范围，运行 join 同项目 attempt/step；倒序 `(timestamp,id)` keyset。已有五项真实数据库测试覆盖还原、权限撤销、跨范围／类型错配及分页。未修改生产上传语义、迁移、权限或审计写入路径。刷新遗漏已修复；未发现其他确定的阻塞性历史功能问题。这是本分支自审，仍交总管复核。

### 验证与证据

- 最终前端全套：21 文件、235 项，0 失败／跳过；lint 退出 0，保留原 9 warning；build 通过。
- 最终 Java 21 单元：79 项、0 failure/error/skipped，其中新增守卫 7 项。
- 最终两个真实故障恢复方法均通过（2 项、0 failure/error/skipped）：`AssetDatabaseIT.scannerUnavailableAndTimeoutRemainRetryableWithoutPublication`、`AssetTerminalOutcomeIT.scannerTransientFailureSameKeyRemainsRetryable`。
- 前者修复前单独复现 `Owned scanner has no real published endpoint`，修复后通过真实扫描超时、恢复和同键重试发布。根因：Docker Desktop 暂停容器时 `NetworkSettings.Ports` 为空；原 post-pause 检查在调用者 try/finally 之前抛错，留下暂停的 clamd，后续共享资源测试连锁超时。新建独立带随机标签的 probe 也复现运行时有 Ports、暂停时空；每次操作核对标签，只删除本 probe。
- 守卫仍要求本轮标签、容器 ID、启动时间、运行／暂停状态及配置绑定一致；暂停期间仅允许 live Ports 缺失，有值必须相同；恢复后实际发布端口必须严格匹配。暂停后检查异常会尝试恢复同一可证明归属的进程，再抛原异常。七个单元回归：post-pause 检查异常恢复；初始未知归属拒绝；进程替换拒绝；配置绑定变化拒绝；进程重启拒绝；暂停后归属丢失拒绝；恢复后实际发布端口变化判失败。失败恢复不扩大到其他容器。
- 本轮完整集成（首次兼容修复后、抽取同一守卫供单元测试前）：157 项、1 failure / 5 errors / 0 skipped，仍 **失败**；HistoryDatabaseIT 五项通过。最终守卫抽取后跑了上述全单元与两个真实方法，未重复跑整套。原本 2F/21E 的扫描器连锁故障已消除，不能把剩余失败当绿色。
- 剩余 error 方法：`AssetDatabaseIT.realClamdCleanAndEicarProduceSafeTerminalStates`；`AssetTerminalOutcomeIT.infectedCompleteSameKeyDifferentRequestIsRejected`、`infectedCompleteSameKeyReplaysFrozenTerminalRejection`、`infectedCompleteNewKeyReturnsAlreadyRejectedWithoutRescan`、`terminalReplayReauthorizesRevokedDisabledAndExpiredPrincipals`。均为 EICAR 上传阶段 `Storage unavailable`。
- 剩余 failure：`OidcCallbackSecurityIT.realOidcAssetHttpTerminalCompleteFreezesRejectionAndReauthorizesReplay`，EICAR PUT 预期 200，实际 503。
- 对应本轮 00:48—00:50 时间窗，Windows Defender Operational 有 6 条 1116 `Virus:DOS/EICAR_Test_File` 检测，均为 Java 进程生成的 `test365alm-upload-*.bin`，另有 1117 隔离。已核对六个失败均上传 EICAR；这是明确宿主环境干扰证据。未改 Defender 设置／排除项，不把真实 ClamAV断言改为 mock、skip 或重试。原始事件路径只在忽略证据中，未提交。
- 忽略证据根目录 `local-evidence/audit-history/`：`retest-red.log`、`review-web-final.log`、`review-lint.log`、`review-build.log`；`scanner-repro.log`（红）、`scanner-green.log`（首次绿）、`scanner-guards.log`、`scanner-final.log`（最终 79 单元＋2 真实方法）；`review-integration.log`、`review-failures.json`、`defender-events.json`。旧完整报告保存在 `review-baseline/failsafe-reports/`，本轮完整报告快照在 `review-full-reports/`，避免最终专项报告覆盖；完整日志的 157 项汇总为本轮全套权威计数。
- trail build 完成、trail check 退出 0，无冲突（关键词提醒保留）。未推送、未开 PR、未移动 dev，未触碰现有演示服务或其他项目容器；两个已有自动刷新文件继续保留。

### 真实浏览器与集成依赖

三条增加审计断言的真实 Keycloak 浏览器流程仍 **NOT_RUN**：缺少真实 CI-owned R03 资源、有效 run ID/项目标签和资源 manifest；本进程无 `GITHUB_RUN_ID`、`GITHUB_RUN_ATTEMPT`、`R03_COMPOSE_PROJECT`、`R03_RUN_ID`。不得通过设置假 CI 变量放行。合成夹具不替代这三条验收。

复核时 dev 已从 a667343 前进到 `158fc98`，均为总管文档更新；`60e1989` 仍不是 dev 祖先，目录树业务尚未入 dev。因此本轮不集成其他线；总管批准目录树集成并通知后，再将最新 dev 合入本分支并保留双方 UI 改动。原 W1/W2 接受附件基线只为历史局部决定；当前审计要求完整集成全绿与真实验收，仍未满足，**可供审查但不可合并**。

### Linux 测试进程最小方案评估（未执行，待总管明确权限边界）

现有 `.github/workflows/ci.yml` 在 Ubuntu host 运行 Java21/Maven＋Python3＋Docker；没有现成容器内 Maven runner。`r02_resource_guard.py`/`r03_resource_guard.py` 的 manifest 以 Compose project 资源归属为前提，不能直接宣称覆盖新的裸 runner。当前 Docker Engine 为 Linux Docker Desktop，本机无 Maven/JDK21 runner 镜像。

最小方案：固定 digest 的 Linux runner 包含 Java21/Maven、Python3和 Docker CLI（正式 scanner provision 测试会调用 Python 工具再 docker exec）；只将 Git 受控文件归档复制入容器自己的可写层，源码、target、`/tmp`、Maven缓存均不绑定 Windows 宿主；报告完成后才复制出容器。使用本机 Docker socket 创建兄弟 Testcontainers，显式验证 `host.docker.internal` 到临时发布端口可达，不使用日常 .env，不设置 CI-owned 浏览器变量，不用 privileged/DinD。必须先记录 daemon/context、随机 run 标签、runner ID／启动身份和每个新增资源精确 ID，操作／清理逐一核对归属，拒绝项目级清理，保留 Ryuk 正常回收。

限制／BLOCKED：这需要新增固定 runner 镜像／测试工具依赖，以及向 runner 挂载 Docker socket（会授予本机 daemon 控制权限），现有 Compose 守卫没有直接支持的入口。按总管最新要求，先说明这两个边界并等明确决定，再制作测试设施及离线归属回归；本轮尚未拉取／构建 runner 或挂 socket。全绿门槛与真实浏览器缺口继续保留，不能因 Linux 方案待定自行豁免。

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
