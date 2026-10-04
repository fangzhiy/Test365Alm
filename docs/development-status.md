# Test365Alm 开发状态

## R06-M09-001 当前状态（首个手工执行切片，等待审核）

- 已从实际 `feat/r05-m08-001` 基线建立 `feat/r06-m09-001`；本地起点为 `f8e8d89df15a8fe62b726cc478d90416e4679567`，对应 Git Data API 审核基线 `845acde47e5f9cd2c330878b383aa2ce0611b992`。本轮原始输入已归档到 [`docs/progress/inputs/R06-M09-001.md`](progress/inputs/R06-M09-001.md)。
- 新增 V12 迁移、受限 runtime/RLS 表结构和执行服务：测试集、固定用例修订实例、不可变 manifest、运行/尝试/步骤、暂停/继续/完成、重跑、幂等、execution event、audit_event 和 Outbox 同事务。接口契约见 [`contracts/manual-execution.json`](../contracts/manual-execution.json)，决策见 [`ADR-019`](adr/019-r06-manual-execution-slice.md)。
- 前端新增真实 API 驱动的“测试集与手工运行”工作台；用例修订选择器从 `/tests` 和 `/revisions` 读取，展示步骤结果、尝试历史和读写权限状态。无静态假数据接通。
- 本机系统 Maven 单元/边界测试 `mvn -B -ntp test`：30 tests，0 failures/errors/skipped；前端 9 files/113 tests、`tsc --noEmit`、build 通过。`npm run lint` 退出 0，仅既有 React effect/ref warnings。Testcontainers 集成 `mvn -B -ntp -Pintegration verify` 已实际运行但因本机没有可用 Docker 环境在 11 个 IT 的容器启动阶段失败；不能把真实 PostgreSQL 结果写成通过，等待 Ubuntu CI。
- 首个远端 PR CI `37181708139`（checkout `dc3e7cda54d92d276652b344c787573e27c696af`）暴露三项接入问题：旧升级断言未计入正式 V12、迁移失败探针与正式 V12 同号、M09 集成类使用无 Web 上下文导致缺少 `HttpSecurity`。随后 Push `37182544571` 已验证前两项修复和其他回归通过，但 `ManualExecutionDatabaseIT` 暴露 runtime 清理过期幂等记录缺少 DELETE 权限。第三轮 [37183266578](https://github.com/fangzhiy/Test365Alm/actions/runs/37183266578) 进一步确认 V13 无条件授权会在探针数据库缺少 runtime 角色时先失败；本地 `417ee6ba99223b064c66aa39e22eb0fd2b0adc94` 已改为条件授权，并将探针顺延为正式 V13 后故意失败 V14，发布后的最终 CI 待核对。
- M09 只实现本轮最小闭环；测试树、批量、参数/配置/环境、调度、Agent、附件、截图、缺陷关联、离线、导出和复杂报表仍未实现。M08、M09 不标记整体完成。

详细记录见 [`R06-M09-001.md`](progress/runs/R06-M09-001.md)。

## R05-M08-001-FIX02 当前状态（等待最终审核）

- 本轮继续 `feat/r05-m08-001` / PR #5（base `feat/r04-m07-001`）。开始时 GitHub API 核对远端 head 为 `7722dcfa9533ba19a90a08e847f8ec2c2aa54d4f`，PR Open、未合并；普通 Windows Git fetch 仍因 Schannel `SEC_E_NO_CREDENTIALS (0x8009030E)` 失败，未 reset、回退或强推。
- 历史 Push `37118199450` 和 PR `37118202061` 均因 planning-tools 找不到 `docs/progress/inputs/R05-M08-001-FIX01.md` 失败；PR 实际 checkout 为 `ae38abbfbb7906b27430cb25637a2c5e9f5b8b17`。远端快照同时缺少 `docs/adr/018-r05-sealed-test-steps.md`。本轮从本地完整文件恢复并在最终交付中补齐，未把此前缺失写成历史已发布。
- G02：`TestCasesPanel` 在同一项目直接 `access=null` 或移除 `test:read` 时，先递增读写轮次、取消请求和定时器，再清理数据；Context 还比较权限指纹，传输层忽略 AbortController 时旧回包也不能更新状态。MEMBER→VIEWER 仍保留只读读取；权限恢复时使用新请求，不恢复失效期间草稿或操作意图。新增直接失权、迟到成功/失败/AbortError、无后续详情/历史请求和恢复后按钮可用回归。
- 本地验证：前端 Vitest 8 files/108 tests、`npm run lint`、`npx tsc --noEmit`、`npm run build` 均通过；lint 只有既有 React/ref/effect warnings。Python 工具 56 tests 和 `validate_package.py` 通过；真实 Testcontainers 仍受本机 Windows JNA/Docker 限制，按 F01/F03 既有 Ubuntu CI 证据保留。
- G01/G03 的最终状态、远端文档回读、代码/交付 SHA 和最终 Push/PR CI 以本轮发布后核对及交付回复为准；历史 FIX01 失败不再写为 pending。完整 M08 的测试树、复制、配置/参数、执行、结果、附件和需求关联仍未实现。

详细记录见 [`R05-M08-001-FIX02.md`](progress/runs/R05-M08-001-FIX02.md)。

## R05-M08-001-FIX01 当前状态（等待 PR #5 审核）

- 本轮继续 `feat/r05-m08-001` / PR #5（base `feat/r04-m07-001`），开始前远端 PR 仍为 Open、未合并，远端 head 为审核交付 `47c3dd3788ca3572057af3b562ba408b848621cf`，PR merge checkout 基线为 `066d3bed874d507174046d9b3815d74f81027471`。本地从 `d6b67dc9f7593ff7cd9192b09e603503c6a2774b` 继续，未 reset、回退或强推；Windows Git fetch/push 的 Schannel 凭据限制继续保留，远端发布通过 GitHub Data API 核对。
- F01：新增正式 V11 迁移，在完整步骤快照写入后封存 `test_revision.sealed_at`；V10 已存在行以 `created_at` 作为升级封存证据。受限 runtime 对已封存历史/当前修订追加步骤在 RLS 边界拒绝，正常新建/追加修订、稳定 step_key、幂等重放及既有 V10 数据保留测试继续覆盖。迁移失败探针同步由正式 V11 后执行故意失败 V12，不改写 V1—V10。
- F02：`TestCasesPanel` 将列表分页和详情读取状态分开交接；详情成功/失败会结束列表 loading，旧分页请求的成功、失败、AbortError 和 finally 不能覆盖新详情；项目/权限范围变化清理 saving 与旧数据；详情/冲突读取期间步骤输入、上下移、下移、移除、新增均禁用。新增 8 个确定性组件回归，前端当前为 8 files/100 tests。
- F03：CI-owned Keycloak 手工用例改为真实三主体流程：管理员创建并授权，普通 `PROJECT_MEMBER` 登录后创建/刷新/编辑调序/保存新修订/查看旧修订，独立 viewer 只读且无新增/保存入口；页面操作前通过真实 `/api/v1/me/permissions` 核对角色和权限。测试资源仍由本轮隔离 Compose/数据库播种，生成器新增随机 `R03_MEMBER_*`，不提交凭据。
- 本地实际通过：系统 Maven `mvn -B -ntp test` 24 tests；`mvn -B -ntp -DskipTests test-compile`；前端 `npm run lint`（0 error、既有 warnings）、`npm run test:run` 8 files/100 tests、`npm run build`、`npx tsc --noEmit`；Python `python -m unittest discover -s tools/tests -v` 56 tests；`python tools/validate_package.py`。本地集成 `mvn -B -ntp -Pintegration verify '-Dbuild.commit=local-r05-m08-fix01'` 退出码 1，10 个 Failsafe 类均在 Testcontainers 启动阶段因 Windows JNA `jnidispatch.dll`/Docker named pipe 权限失败，非业务断言；因此真实 PostgreSQL/Keycloak 及浏览器验收须由隔离 Ubuntu CI 提供。
- 本轮代码验证提交为本地 `8075216`（含 V11、前端/浏览器修复、升级计数修正和报告门禁；其父链含 `2eea12f`），GitHub Data API 代码提交为 `8d211ad06b336ec02521d32db3e62b4c20f72b23`。Push CI [37117461103](https://github.com/fangzhiy/Test365Alm/actions/runs/37117461103) 与 PR CI [37117463767](https://github.com/fangzhiy/Test365Alm/actions/runs/37117463767) 均 success；Push checkout 为 `8d211ad...`，PR merge-ref checkout 为 `0093f4df4260ae5485149e3401bac0b484d77369`。server Surefire 24、Failsafe 59（`TestCaseDatabaseIT` 10、HTTP 1、迁移 1、OIDC 14、需求 13、其余 R02/R03 升级类），browser 9/0/0/0，workers=1、retries=0。M08 其余测试树、复制、配置/参数、执行、结果、附件和需求关联仍未实现。

详细记录见 [`R05-M08-001-FIX01.md`](progress/runs/R05-M08-001-FIX01.md)。

> 当前轮次：`R05-M08-001`。已在独立 `feat/r05-m08-001` 分支实现 MANUAL 手工测试用例第一切片（列表、创建、详情、步骤快照、不可变修订、项目权限、ETag/幂等和前端工作台）；真实 PostgreSQL/Keycloak 及远端 CI 结果以本轮记录为准，尚未将 M08 或完整 ALM 标为完成。

## R05-M08-001 当前状态（已完成本轮实现，等待审核）

- 起点为远端已核查的 `feat/r04-m07-001` head `32ee886d3ea4745da3af14fd23886abf777442ea`；本轮分支 `feat/r05-m08-001`，PR #5 Open、base `feat/r04-m07-001`，依赖 PR #4。Windows Git fetch/push 受 Schannel 凭据限制，远端分支通过 GitHub Data API 核对；本地代码验证 HEAD `875404e874b54f195ce7cdaaf620b2944eb509a5`，远端代码提交 `0f8b042487a0dc860f09fe81ee0a3285ae1c23b8`，两者以文件 blob 对应，不将本地 SHA 当作远端 SHA。
- V10 与 `tests` HTTP/service 边界已实现：项目内显示编号、MANUAL 类型、稳定 step_key、不可变修订/步骤、强 ETag/If-Match、持久化幂等和同键并发、同事务审计/Outbox、runtime RLS/受限权限；成员读写、viewer 只读、跨项目/跨租户和撤权/停用会话矩阵已加入真实 OIDC HTTP 测试。审计策略收紧为精确 object/action/target，HTTP 序号必须为正整数。前端工作台保留请求轮次、取消、项目切换清理；未知创建结果复用同一幂等键，写入被拒绝不会误清空可读范围。
- 已实测本地基础检查：系统 Maven `mvn -B -ntp test` 24 tests、前端 8 files/92 tests、lint/build、Python 工具 56 tests 和规划校验均通过；lint 仍有既有 React warnings。Windows Docker/Testcontainers 的真实 PostgreSQL/OIDC 集成在启动前因 `jnidispatch.dll`/Docker named pipe 权限阻塞，不能写成通过。Push CI `37101518712`（head/checkout `0f8b042487a0dc860f09fe81ee0a3285ae1c23b8`）和 PR merge-ref CI `37101522075`（checkout `7d86cfa28229eec4579ba0e6a1a09c5be4c62428`）均已 success；server Failsafe 58 tests、`TestCaseDatabaseIT` 9、HTTP 1、迁移 1、OIDC 14，全部 failures/errors/skipped=0；浏览器 9/0/0/0，workers=1、retries=0。
- 本轮只覆盖 M08 手工用例/步骤第一条闭环；测试树、复制、配置、参数、被调用测试、执行/结果、附件、需求关联、生产迁移和完整 M08 验收继续保留为未实现/未验证。

最后更新：2026-10-03
状态：`IMPLEMENTED / LOCAL_DOCKER_BLOCKED / PUSH_AND_PR_CI_PASS`

> 当前轮次：`R04-M07-001-FIX02`。GitHub 代码提交为 `3e6055a9c1f33dbca34e84c977fb0ad1a19a9df2`，Push CI `36968392677` 与 PR CI `36968396770` 均成功；Windows Testcontainers 仍受 Docker/JNA 权限限制。详见文末 FIX02 状态与 [`docs/progress/runs/R04-M07-001-FIX02.md`](progress/runs/R04-M07-001-FIX02.md)。

## R04-M07-001-FIX01 当前状态

- 本轮继续 `feat/r04-m07-001` / PR #4（base `feat/r03-m03-002`），审核基准为 `5e9f0eae6598835301c053cdfcc374ab68051fc0`；远端分支仍由该审核 head 指向，PR Open、未合并。Windows Git fetch 仍因 Schannel `SEC_E_NO_CREDENTIALS (0x8009030E)` 失败，未 reset、回退或强推；发布前后使用 GitHub Git Data API 核对父提交、树和 ref。
- FIX01 新增 V8 受控迁移：预检并拒绝越界引用，current revision、revision/outbox/idempotency 绑定同租户/项目/需求；限制 runtime writer 为有效 `PROJECT_ADMIN`/`PROJECT_MEMBER`，viewer 的直接 SQL 写入、revision/outbox 修改和截断被拒绝；审计动作与目标收口。V7 已执行脚本不改写。
- 后端幂等记录按主体/路由作用域检查过期，保存完成响应快照，重放前重新授权；规范化哈希区分省略字段与字面量 `<null>`。审计/Outbox 权限故障回滚和同 ETag 并发一胜一 412 均有真实 PostgreSQL 测试，报告门禁从 3 个提升为 9 个指定用例。
- 前端读写轮次、AbortController、卸载/退出/项目切换清理、真实 ETag、稳定 Idempotency-Key、412 草稿保留和失权清理均已补齐；新增 API 响应丢失重试和缺失 ETag 测试。
- 本机已通过：系统 Maven `mvn -B -ntp test`（19 个 Surefire 用例，0 失败/错误/跳过）、`mvn -B -ntp -DskipTests test-compile`、前端 Vitest 6 文件/51 测试、lint（0 error，3 个 React 警告）、Vite build、Python 工具和契约 JSON 校验。Wrapper 的 Windows PowerShell 启动失败与系统 Maven 结果分开记录。
- 本机 Testcontainers/Failsafe 仍因 Docker/JNA named pipe 权限在测试方法前阻塞；隔离 Ubuntu CI 已实际执行 39 个集成测试（含 `RequirementDatabaseIT` 9/9）并通过。迁移失败探针已随 V8 正式迁移调整为正式 V8、故意失败 V9。历史失败 Push `36952755554`/PR `36952759123`（V8 SQL 关联写法）和 Push `36953271971`/PR `36953276257`（升级计数、RLS 过滤断言、旧 V8 故障探针）保留；最终 Push `36954097749` 与 PR merge-ref `36954101050` 均为 success。旧 V7 已完成记录、生产部署和完整 M07 均不因本轮改动标记完成。

最后更新：2026-10-02
当前轮次：`R04-M07-001-FIX01`
状态：`IMPLEMENTED / LOCAL_DOCKER_BLOCKED / PUSH_AND_PR_CI_PASS`

## R04-M07-001 当前状态

- 本轮从实际分支 `feat/r03-m03-002` 的 `07224db903b542318472fdd52ca2f7fb7d3d7aca` 创建 `feat/r04-m07-001`，未回退或覆盖既有 R03 修改。PR #3 仍为 Open、未合并（base `feat/r03-m03-001`）；本轮 PR 以其实际分支为依赖 base，不把需求代码追加到 PR #3。Windows `git fetch origin --prune` 仍因 Schannel `SEC_E_NO_CREDENTIALS (0x8009030E)` 未完成，远端状态以 GitHub API/推送核对为准。
- 已实现项目根级需求最小闭环（初始代码提交 `befe29449434af5b43b6135504bf8b76e333eee2`，最终代码测试提交 `b6337dcfdf95f0f790f0af0ff125f334a7145d49`）：V7 受控迁移、项目成员权限边界、稳定 UUID 与项目内显示编号、行锁编号分配、幂等创建、具体 `If-Match` 编辑、追加且只读修订历史、审计与 Outbox 同事务、运行时 RLS/列级授权，以及前端真实项目上下文工作台。未实现需求树、富文本、附件、评审、追踪、删除、导入导出或 AI。
- API 为 `POST/GET /api/v1/projects/{projectId}/requirements`、`GET/PATCH /{requirementId}`、`GET /{requirementId}/revisions` 和单修订查询；前端不接受手工 scope，PROJECT_VIEWER 只读，412 保留编辑草稿。
- 本机工具：Java `17.0.2`、Maven `3.9.14`（POM 编译目标仍为 Java 17）、Node `v26.0.0`、npm `11.12.1`、Python `3.12`。CI 既有基线使用 Java 21/Node 24；本机版本差异不写成目标环境已验证。
- 已实际通过：后端 Surefire 19 个、前端 5 个文件/45 个测试、Python 工具 52 个、前端 lint（仅既有 React effect 警告）、前端构建、后端 test-compile、契约 JSON 和敏感信息扫描。`npm ci` 首次受 Windows 全局缓存 EPERM 影响失败，改用仓库内临时缓存后成功；Maven Wrapper 本机 PowerShell 脚本以 `icm : Cannot index into a null array` 退出码 1，系统 Maven 19/0 结果单独记录，未混写为 Wrapper 成功。
- 本机真实 PostgreSQL/Testcontainers 集成仍在 Failsafe 启动前因 Windows JNA `jnidispatch.dll`/Docker named pipe 权限失败；CI 已在隔离 Ubuntu 上执行 `RequirementDatabaseIT` 3/3、升级、迁移失败和 readiness 恢复。真实 Keycloak 浏览器验收本机未运行，CI 已完成真实需求 UI 流程。
- I01/I02/I03/I06/I07/I08/I09/I10 已由 Push/PR CI 实际通过；I04（真实并发 PostgreSQL）与 I05（完整 If-Match/CSRF HTTP 场景）仍为 `NOT_RUN`。本机 Docker 阻塞仍保留，不把本地未运行写成通过。
- 本轮不标记 P0、M07 或完整 R04 完成；旧 ALM 样本、完整需求能力和生产部署继续保留为未验证/后续范围。

最后更新：2026-10-01
当前轮次：`R04-M07-001`
状态：`IMPLEMENTED / LOCAL_DOCKER_BLOCKED / PUSH_AND_PR_CI_PASS`

## R03-M03-002-FIX03 当前状态

- 本轮从审核交付 `b4b2486b801b529dfe0da04c16f843a62405115a` 继续，实际分支为 `feat/r03-m03-002`，PR #3 仍 Open、未合并，base 为 `feat/r03-m03-001`。Windows Git fetch 仍受 Schannel `SEC_E_NO_CREDENTIALS (0x8009030E)` 限制；未 reset、回退或强推，远端分支和 PR 使用 GitHub API/普通 push 核对。
- 页面修复代码分组提交为 `d2f638d07510960b4f688c9564bf6a4a4e137bb6`、`cd18d3253a0ee9cabfc0fb421ff638f1cc0342a7`，最终诊断修正代码 SHA 为 `d21922eb2f87aedf3f8816a62badb0675809370a`。修复 `ProjectAccessPanel` 竞态下切换租户后 loading 永不结束的问题；项目 UI/完整浏览器套件固定单 worker、零重试；增加命名步骤、状态/HTTP/DOM/控制台脱敏上下文；诊断快照读取使用 1 秒有界超时，避免诊断本身耗尽测试预算。
- 历史浏览器失败保留：`36834802125`/`36834808273` 的项目 UI 120 秒超时以及 `cd18d32` 的 `context.newPage()` 超时均已单独记录；后者根因是无界诊断快照读取阻塞 Playwright 协议队列，不把最终 `viewerContext.close()` 清理异常误写为首次业务失败。
- 最终代码 Push CI [36846431316](https://github.com/fangzhiy/Test365Alm/actions/runs/36846431316) 六个 Job 全部 success，browser checkout 为 `d21922eb2f87aedf3f8816a62badb0675809370a`；PR CI [36846438107](https://github.com/fangzhiy/Test365Alm/actions/runs/36846438107) 六个 Job 全部 success，merge-ref checkout 为 `e61427a3fa4ac1e5f284485a5d9d4e7dedbe5d2d`。两次均有项目 UI 独立 1/1 与完整七用例 7/7（1 worker、0 failures/errors/skipped）报告，资源清理 PASS；浏览器制品分别为 `11153393376` 与 `11153811215`。
- H11/H12：本轮 Push 与 PR merge-ref 均实际完整通过，更新为 `PASS`。固定代码/配置下未在本机或 CI 做三次独立完整套件重复运行，稳定性三次建议记为 `NOT_RUN`，不扩大 H11/H12 之外的结论。Windows Testcontainers/JNA 集成仍阻塞本机真实 PostgreSQL 复跑，Ubuntu CI 已提供隔离证据。
- 本轮未进入需求、用例或缺陷模块；不代表完整 M03、生产安全审查、MFA、多节点会话、旧 ALM 兼容或生产部署完成。

最后更新：2026-10-01
当前轮次：`R03-M03-002-FIX03`
状态：`CODE_PUSH_CI_PASS / PR_MERGE_CI_PASS / H11_H12_PASS`

## R03-M03-002-FIX02 当前状态

- 本轮实际开始核对时分支为 `feat/r03-m03-002`，远端/本地 head 为 `648ee43e4cff787d5a06bbb354f21bf9167000e4`；历史审核基线 `0a03c357478574c91526e60adf3b76b61fd87abe` 和 PR #3（base `feat/r03-m03-001`）均保留。PR #3 仍 Open、未合并、未部署、未修改默认分支或保护规则。Windows Git fetch 继续受 `SEC_E_NO_CREDENTIALS (0x8009030E)` 限制，未 reset、回退或强推；远端 head、PR 和 CI 使用 GitHub API/普通 push 核对。
- 本轮代码修正已提交为 `a08e6c9e54b0f0695fc11d0599c8fc9fa1c93d7b` 并推送：生命周期状态检查与项目列表过滤、严格 CI 运行 ID 守卫、项目 UI E2E 的稳定控件定位和并发更新后的项目 ID 选择。此前 V5/V6、runtime datasource/RLS、成员版本/最后管理员/审计、未 bootstrap HTTP、前端项目面板和 manifest 清理均保留。
- 本机最终验证：Java 17.0.2/Maven 3.9.14 下完整后端集成 30 个测试通过（Surefire 17、Failsafe 30，失败/错误/跳过均 0）；前端 Vitest 32 个通过，lint/build 通过，Playwright 列出 7 个用例；Python 工具 49 个通过。真实 Testcontainers/Keycloak 浏览器本机证据仍不依赖 Windows 环境，使用 Ubuntu CI 的真实报告。
- 最终代码 SHA 的 Push CI [36830534971](https://github.com/fangzhiy/Test365Alm/actions/runs/36830534971) 六个 job 全部 success，浏览器报告 7/7、0 failure/error/skipped，checkout 为分支 head `a08e6c9e54b0f0695fc11d0599c8fc9fa1c93d7b`。PR CI [36830539105](https://github.com/fangzhiy/Test365Alm/actions/runs/36830539105) 使用 merge ref checkout `e0690b48672792ad280d045963f78702889faa28`；其余五个 job success，但 `oidc-browser` 项目 UI 测试超时，重跑 job `110268103116` 后同样失败。该 merge-ref 限制未被改写为通过。
- H00-H12 的逐项证据、Push/PR checkout SHA 和剩余限制见 [`R03-M03-002-FIX02.md`](progress/runs/R03-M03-002-FIX02.md)。本状态页和本轮记录不把 Push 成功冒充 PR merge 成功，也不把本轮标为完整 M03。

最后更新：2026-10-01
当前轮次：`R03-M03-002-FIX02`
状态：`CODE_PUSH_CI_PASS / PR_MERGE_CI_FAIL`

## R03-M03-002-FIX01 当前状态

- 本轮继续使用 `feat/r03-m03-002` / PR #3（base `feat/r03-m03-001`），审核基准为 `42670b869d399d04c0a6e4e7ad5eab83ef5fdbaa`。开始前工作区位于该基准；远端 fetch 因本机 Git Schannel 凭据错误 `SEC_E_NO_CREDENTIALS` 未能完成，未执行回退或强制覆盖。PR #3 仍未合并。
- 代码最终提交为 `19009f2`（完整 SHA 及远端核对见本轮记录）。修正内容：V4 迁移收紧项目/审计 RLS；TENANT_ADMIN 只能看到显式加入的项目；租户成员撤权立即失去项目访问；PROJECT_ADMIN 仍可在其租户内读取成员候选；前端只读、请求轮次、超时、卸载和退出清理；CI 增加真实 Keycloak 双用户项目访问报告门禁和失败摘要。
- 本机前端 28 个测试、后端 Surefire 17 个测试、Python 工具 44 个测试均已通过；前端 lint/build 通过。真实 PostgreSQL/Testcontainers 与 Keycloak 双用户浏览器只能依赖 Ubuntu CI，本机 Docker named pipe/JNA 权限仍阻塞，不能把本地跳过写成 PASS。
- `19009f2` 的 Push run [36811944675](https://github.com/fangzhiy/Test365Alm/actions/runs/36811944675) 与 PR run [36811948812](https://github.com/fangzhiy/Test365Alm/actions/runs/36811948812) 均六个 job success；Push checkout 为分支 head，PR merge checkout 为 `fae29d49c0b11ea8d1abe97dc3fb6b1471238097`。文档交付提交会再次触发 CI，不把文档提交 SHA 冒充代码测试 SHA。
- 本轮不把整个 M03、H00-H12 或 P0 标记完成；未覆盖的需求、用例、缺陷、MFA、集群会话、生产 IdP/部署和旧 ALM 兼容继续保留。

详细执行证据见 [`R03-M03-002-FIX01.md`](progress/runs/R03-M03-002-FIX01.md)。

## R03-M03-002 当前状态

- A 段已在 `feat/r03-m03-001` / PR #2 的依赖分支中完成，起始 `6489932ebd93542f80a036709c58e3fdab06082e`，修正代码提交 `75b9d9304ceff2c38c40d3c9d9be96957db88145`。
- A 的 Push run [36373203011](https://github.com/fangzhiy/Test365Alm/actions/runs/36373203011) 和 PR run [36373206437](https://github.com/fangzhiy/Test365Alm/actions/runs/36373206437) 均六 job success；PR #2 仍 Open、未合并。A 的详细记录见 [`R03-M03-001-FIX02-CLOSE.md`](progress/runs/R03-M03-001-FIX02-CLOSE.md)。
- A 只修正 OIDC HTTP 回归证据：同一 HttpClient/CookieManager 失败后合法重试复用，以及每个非法变体的两个哨兵、existing/absent 主体和完整 principal 快照。A 未改写 FIX02 历史。
- B 已在新分支 `feat/r03-m03-002` 完成最小项目访问切片，代码提交 `89b2f301f2c74f39e0cf5b1ce68b7a324d74d551`，依赖 PR #3（base `feat/r03-m03-001`，PR #2 仍未合并）。项目/成员/固定角色/撤权/数据隔离未追加到 PR #2。
- B 的 Push run [36375693671](https://github.com/fangzhiy/Test365Alm/actions/runs/36375693671) 与 PR run [36375727963](https://github.com/fangzhiy/Test365Alm/actions/runs/36375727963) 均六 Job success；PR merge checkout 为 `a77a3c2cf679eff2d304e69e0d48ad4578bf860b`，分支 head 为 `89b2f301f2c74f39e0cf5b1ce68b7a324d74d551`。本轮记录见 [`R03-M03-002.md`](progress/runs/R03-M03-002.md)。
- B 已加入 Flyway V3、复合外键、基础 RLS、固定项目角色、事务审计、同源 CSRF 前端面板和严格 CI 报告门禁；前端 24/24、后端 Surefire 17、CI PlatformDatabaseIT 8 与既有 OIDC 7 均无失败。H05-H07、H09、H11 的完整 HTTP/浏览器/并发场景仍为 NOT_RUN，不能将此切片标为完整 M03。

## R03-M03-001-FIX02 当前状态

- 本轮从审核基准 `d8240fd0f8f006326f7a0188d900dca4f119917e` 继续在 `feat/r03-m03-001` / PR #2（base `feat/r02-m02-001`）实施；PR #1 仍未合并。本轮代码/测试提交为 `d52598d8d1224e1fad82e853be96024d243d04bd`，交付文档提交 SHA 在最终回复给出，不在本记录自引用。
- 已补齐真实 HTTP OIDC 回调证据：合法对照、错误签名、错误 issuer、错误 audience、过期和错误 nonce 六类 token 回调，以及失败后重新发起合法授权恢复；同一 Cookie 的 `/api/v1/me` 和 PostgreSQL `principal` 全字段快照均被断言。测试使用回环本地协议 IdP 和固定摘要 PostgreSQL Testcontainers，不读取日常 `.env`。
- 已加入 `verify_r03_oidc_http_report.py`，CI server job 必须发现 7 个指定 `OidcCallbackSecurityIT` 用例且 failures/errors/skipped 全为 0；原 planning、web、readiness-recovery、migration-failure、oidc-browser 与 server 六个 job 保留。
- 代码提交的 [Push CI](https://github.com/fangzhiy/Test365Alm/actions/runs/35963117055) 与 [PR CI](https://github.com/fangzhiy/Test365Alm/actions/runs/35963119731) 均成功，六 job 和 backend-evidence 等白名单制品存在；PR merge checkout ref 当时为 `5ee8fd859802cb5f974d14356b7f98aae6814c77`。文档交付提交会另触发 CI，最终状态在任务回复中核对。
- E05/F03 在本轮定义范围内为 PASS；这只表示五类非法 token 的真实应用回调、会话拒绝及主体不变证据完成，不代表全部 OIDC 标准认证、生产安全审查或完整 M03 完成。
- 详见 [`R03-M03-001-FIX02.md`](progress/runs/R03-M03-001-FIX02.md)；FIX01 记录和历史失败不改写。

## R03-M03-001-FIX01 当前状态

- 在原 R03 分支和 PR #2（base `feat/r02-m02-001`）继续补修；PR #1 仍是未合并依赖。本轮未合并、部署或恢复定时任务。原 `R03-M03-001.md` 保留为产生时的历史快照，不能用其 `NOT_RUN` 误判当前，也不能静默改写曾经失败的 CI。
- 已修复 Linux Keycloak realm 导入权限：CI 将本轮生成的文件交给镜像 UID 1000 且设为 0400；启动前验证 issuer/文件可读性，退出前保存脱敏容器状态和日志，报告要求非零测试数与零失败/跳过。`276185e` 的 Push/PR 六个 Job 已全绿，证实原浏览器 Job 在 discovery 前失败与文件权限有关。
- 已加入标准 Spring Security OIDC provider 的错误签名、issuer、audience、过期、nonce 负向测试及用户加载零调用断言；新增 CI 专属、带本轮资源标签的会话到期、主体停用和 IdP 停启真实浏览器场景。最终测试增强代码 SHA `2992db71afa5c63a3bba24aaf9c5305c9df52ef6` 的 [Push CI](https://github.com/fangzhiy/Test365Alm/actions/runs/35945841031) 和 [PR CI](https://github.com/fangzhiy/Test365Alm/actions/runs/35945844209) 均六个 Job 成功；PR 实际 merge checkout `e98b22d45ae897f0b7ca7d085990d5645635c689`，浏览器报告 5/5、零失败/跳过。
- 历史失败保留：原 `a1f6e89` 的 OIDC 浏览器 Job 在 Keycloak discovery 前失败；`4a63d91` 与 `897acce` 的新增浏览器断言运行后仍有失败，随后修正了异步退出、空闲计时、IdP 恢复后的已有 SSO 会话。详见 `docs/progress/runs/R03-M03-001-FIX01.md`。
- E05/F03 未完全验收：五类恶意令牌经实际 provider 拒绝且不进入用户加载，但尚未逐例经完整 HTTP 回调直接断言 `/me` 与数据库行数。外部审核未发生，不能因 CI 全绿写成完整 M03 安全验收。
- 本轮不包含项目权限、RLS、生产 IdP、实时撤权、全局退出、集群会话或 MFA；旧 ALM/P0 输入和完整 M02/M03 继续保留未验证。

## R03-M03-001 当前状态

- 起始基线：`ceba11332e71f4b2eb8ea71b133e7966bb47639e`，开始时 PR #1 仍 Open、未合并；新分支 `feat/r03-m03-001` 基于该 head，拟向 `feat/r02-m02-001` 提交依赖 PR。代码/测试提交为 `a1f6e89c9a8f384538087af661d5d690e7130daf`。
- 已实现：隔离本地 Keycloak 26.4.4 OIDC Authorization Code + PKCE S256、Spring Security BFF 会话、`principal` V2 Flyway 迁移、独立迁移/运行账户、`GET /api/v1/me`、`GET /api/v1/csrf`、CSRF 保护的本地退出、前端身份状态面板和真实浏览器 E2E。原 R02 健康/版本与工作台保留。
- 本机已验证：真实 Keycloak 浏览器登录—稳定本地主体—退出、无效 state 和非白名单回调拒绝；前端 21 单测、后端 11 单测与 8 个 Testcontainers 集成测试通过；独立临时 PostgreSQL 的 R02 停库恢复与故意失败 V3 迁移回归通过。细项与限制见 `docs/progress/runs/R03-M03-001.md`。
- 尚未完整验证：签名/issuer/audience/过期/nonce 的逐项故障注入、真实会话超时、停用后浏览器重新登录拒绝、IdP 中断时既有/新会话策略，以及本轮远端 CI/PR 结果。均不可写成 PASS；最终 GitHub 状态在交付回复核对。
- 本轮不是完整 M03：项目/成员权限、RLS、跨租户防线、多节点会话、MFA、完整审计和 M03-AC01～AC05 仍待后续。P0 缺少的旧 ALM 版本、Edition、授权样本与全量 M02 事项继续保留。没有生产部署或 PR 合并。

本页保留 R01、R02-M02-001、R02-M02-002 和 R02-M02-003 的历史事实。R02 只实现 M02 工程底座最小切片，不代表整个 M02、P0 或完整 ALM 产品已完成。

## R02-M02-004 当前状态

- 审核基线与起始提交：`d075187e00fe7e43776e84063b7468fe65143733`。开始前重新 fetch，PR #1 仍 open，head `feat/r02-m02-001`，base `chore/r01-status-001`；工作区原本干净。最终代码/测试提交 `4160532fb95308182ef1c8e989d33a62a5557d93` 已推送并由 `git ls-remote` 核对。
- 已实现：两套故障脚本共用资源 guard；首次 up 前拒绝预存项目，资源以本轮 UUID、Docker context/Engine 和实际 ID 绑定；CI 兜底按 manifest 清理，失败使 job 非成功。子进程环境使用白名单并显式绑定 datasource/Flyway，加载位置限定为应用内配置。
- 本机已验证：真实一次性对照项目保留两条哨兵数据与资源 ID；独立临时 PostgreSQL 停库/恢复和失败迁移；合成父环境覆盖未改变目标；Python 35、前端 17、后端单元 9 与集成 3 个测试通过。详见 `docs/progress/runs/R02-M02-004.md`。
- 本轮 CI：首次代码提交 `a9a3c92` 的 Push/PR run [35842323713](https://github.com/fangzhiy/Test365Alm/actions/runs/35842323713) / [35842329475](https://github.com/fangzhiy/Test365Alm/actions/runs/35842329475) 的 planning-tools 因测试依赖本地忽略的 jar 失败；修正测试 fixture 后，最终代码提交 `4160532` 的 Push/PR run [35842756526](https://github.com/fangzhiy/Test365Alm/actions/runs/35842756526) / [35842761456](https://github.com/fangzhiy/Test365Alm/actions/runs/35842761456) 均成功，五个 job 和五类制品均存在。
- 前轮 C02/C07 中有关项目标签即可证明资源归属、CI 直接 `down --volumes` 的子项，经本轮审核发现未充分验证；本轮实现和证据对应 D01-D05。R02-M02-003 记录保留为历史快照，不改写过去结论。
- 旧 ALM 版本、Edition、授权样本及完整 M02/P0 仍未完成；没有合并 PR 或部署生产。

## R02-M02-003 当前状态

- 起始审核提交：`8fb6ce36a18a18bd7c4108706283a8a20eec2f88`；重新 fetch 后 PR #1 仍 open、未合并，分支 `feat/r02-m02-001`，base `chore/r01-status-001`。
- 代码提交：`00e6cbb82568fb677c6ea29db44f5e655c27c64d`；最终进度文档另有交付提交。
- 远端验证：代码/初始记录提交 SHA `178ae40b8880c591459fe95bdefac2adb1eae1d2` 的 Push run [35818955876](https://github.com/fangzhiy/Test365Alm/actions/runs/35818955876) 与 PR run [35818957862](https://github.com/fangzhiy/Test365Alm/actions/runs/35818957862) 均成功，五个必需 job 和证据 artifacts 完整；本次文档收口提交另行记录。
- 已实现：Testcontainers 目标/对照数据库隔离、严格验证脚本与迁移失败启动脚本、前端 HTTP 状态判断、CI 检出 SHA/报告和迁移失败 job。
- 已验证：Testcontainers 集成测试 3 个、前端 17 个、验证脚本离线 7 个、真实停库/恢复和临时 Flyway 失败启动；详见 `docs/progress/runs/R02-M02-003.md`。
- 已验证：最终交付 SHA 的 GitHub Actions；本机 Java 17/Node 26 与 CI Java 21/Node 24 差异继续保留。
- 仍未完成：完整 M02/P0、登录、权限、业务模块、旧 ALM 兼容认证和生产部署。

## R02-M02-002 当前状态

- 起始审核提交：`e10380ba1be542389575a915bc6e3fae8699d81b`；开始前重新 fetch，PR #1 仍 open，分支 `feat/r02-m02-001`，base 为 `chore/r01-status-001`，未发生合并或强制回退。
- 代码提交：`d5bb93f386758429f37dd3a9cfb784582da193bf`；最终进度文档和状态更新另有交付提交，不把文档 SHA 写入代码 SHA。该提交仅修复验证脚本 `.env` 展开时的 eager lookup，前端/后端业务代码未变。
- 已实现：前端请求轮次/取消/卸载清理、严格 JSON/字段/枚举校验；readiness 数据库连接与必要结构区分；真实 PostgreSQL 缺结构测试；回环监听和统一 `.env` 加载；可重复停库/恢复脚本；CI 版本和脱敏报告上传。
- 已验证：前端 14 个测试、后端单元 9 个和真实 PostgreSQL 集成 3 个、打包应用停库/恢复、`127.0.0.1` listener、Compose 配置及规划工具（详见 `docs/progress/runs/R02-M02-002.md`）。
- 已验证：代码 SHA `d5bb93f386758429f37dd3a9cfb784582da193bf` 对应 GitHub Actions run [35727118859](https://github.com/fangzhiy/Test365Alm/actions/runs/35727118859)，planning-tools、web、server、readiness-recovery 均成功；PR 事件 run [35727114339](https://github.com/fangzhiy/Test365Alm/actions/runs/35727114339) 也成功。文档交付提交会触发新的 run，需单独核对。
- 部分未验证：故意失败迁移导致启动失败的独立故障注入未运行；目标 Java 21/Node 24 仅由 CI 负责，当前工作站是 Java 17/Node 26。
- 仍未完成：登录、身份、项目权限、需求/用例/缺陷、执行 Agent、OTA/COM、电子签名、AI 以及完整 M02/P0；旧 ALM 版本/Edition/授权样本继续按 P0 输入阻塞保留。

## R02-M02-001 当前状态

- 起始基线：远端 `origin/chore/r01-status-001`，SHA `cff62dfed99c698a2e32bea85de17e224761eb54`；已重新 fetch，未发现更晚远端提交；工作区起始时干净。
- 任务分支：`feat/r02-m02-001`，基于上述真实远端基线创建。
- 已实现：Spring Boot 平台健康/版本接口、Flyway `V1__platform_metadata.sql`、开发 Compose PostgreSQL、配置示例、React 状态工作台、前后端测试、GitHub Actions workflow。
- 已验证：本地 Docker PostgreSQL 17.11、空库迁移、迁移重跑不重复、元数据保留、后端单元与集成测试、前端 `npm ci`/lint/test/build、打包应用停库/恢复和真实浏览器状态工作台联调。
- 本轮未实现：登录、身份、项目权限、需求/用例/缺陷/执行 Agent、OTA/COM、电子签名、AI 和所有完整业务模块。
- 本轮不把 P0-04、P0 或 M02 标为完成；P0 输入与旧 ALM 样本仍保持原阻塞记录。

### 工具链与配置事实

- 本机：Java `17.0.2`，Node `v26.0.0`，npm `11.12.1`，Docker Engine `29.7.2`，Compose `v5.4.0`，系统 Maven `3.9.14`。
- 可复现命令使用 Maven Wrapper（实际下载/运行 Maven `3.9.16`）、前端 `package-lock.json`、Java 21/Node 24 CI 配置。
- Compose 镜像为 `postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232`，仅发布到 `127.0.0.1`。
- Java 21、Node 24 未安装在当前工作站，不能写成已完成的本地目标环境验证；偏差及选型保留在 ADR-006。
- 本地数据库配置来自被 `.gitignore` 忽略的 `.env` 和 `TEST365ALM_DATASOURCE_*`/集成测试环境变量；没有真实凭据进入提交。

### 接口与迁移

- 契约：`contracts/platform-health.json`；决策：`docs/adr/006-r02-runtime-and-image-lock.md`、`docs/adr/007-platform-health-contract.md`。
- `/health/live` 不依赖数据库，数据库中断时仍返回 200。
- `/health/ready` 对 PostgreSQL 和迁移表做有界探测；正常 200、故障 503、恢复无需重启即可再次 200。
- `/api/v1/version` 来自 Spring Boot build metadata 或配置；没有 Git 元数据时返回 `unknown`，不伪造提交号。

### 当前待验证/阻塞

- 远端分支已推送，PR #1 已创建但未合并；GitHub Actions run 35715927999 已对当前远端交付 SHA `380b905fefebe9917850cc27ee889b5a264111e6` 完成，planning-tools/web/server 均 success。后续提交仍需按对应 SHA 单独核对。
- 浏览器联调已通过隔离 in-app 浏览器 DOM 证据完成；独立截图文件未归档，控制台 error/warn 为空。
- Java 21/Node 24 本机复跑待提供目标运行时。
- 旧 ALM 目标版本、Edition、扩展和三类脱敏样本仍为 B01-B04/B02 未提供输入。

## 实际仓库状态

- 工作目录：`D:\code\project\test365Alm\jihua\Test365Alm_ProjectPackage\Test365Alm`
- Git 仓库：已在本轮初始化；项目根为当前工作目录
- 当前分支：`chore/r01-status-001`
- 起始状态：本轮初始化前无提交；基线提交为 `ec350ce4b991f4bd427fd630332d7e6f7069262e`
- 远端地址：`https://github.com/fangzhiy/Test365Alm.git`
- 远端分支：`origin/chore/r01-status-001` 已推送并核验；代码/保护性提交为 `8c998aac46cbc5a0693640390eb43198359f3f43`，状态记录提交 SHA 在最终回复中给出。
- PR：未创建；用户本轮要求为提交并推送任务分支，未执行合并或分支保护变更

## 已存在与已验证

- 规划包原有 README、32 个模块、契约、规划 CSV/JSON 和离线工具仍在。
- P0 执行资料已建立：`p0/`、`tasks/plan.md`、`tasks/todo.md`。
- `tools/p0_health_check.py` 和对应测试已加入，用于区分规划资产健康与 P0 输入缺失。
- 官方初始化工具已生成 `apps/server` Spring Boot 4.1.1 Maven 工程骨架。
- 官方 Vite React TypeScript 模板已生成 `apps/web`，并已成功执行 `npm install`。
- 以上工程目前是脚手架状态；不能计为健康接口、数据库迁移或业务模块完成。

## 当前代码状态

### 已有

- `apps/server/pom.xml` 已锁定 Spring Boot 4.1.1、Java 17 和 Web MVC/Actuator/JDBC/Flyway/PostgreSQL 依赖。
- `apps/server` 只有 Initializr 主类和空上下文测试，尚未实现业务 HTTP 接口。
- `apps/web` 仍是 Vite 默认示例页面，依赖已安装；模板 lint/build 已通过，尚未连接真实后端。
- `tools/validate_package.py` 已补充对生成目录（`node_modules`、`target`、`dist`、`__pycache__`）的排除，工具测试重新通过。
- `AGENTS.md` 已记录长期约束、轮次记录规则和验证命令。

### 未实现

- `GET /health/live`
- `GET /health/ready`
- `GET /api/v1/version`
- PostgreSQL 受控迁移、Compose 开发环境和故障可观测验证
- 前端真实读取后端版本/健康状态
- 前端和后端的本轮单元/集成测试
- GitHub Actions CI

### 未验证或阻塞

- 旧 ALM 版本、Edition、扩展、客户端和兼容样本：`OPEN / BLOCKED (B01-B04)`。
- 三类客户脱敏样本：`MISSING_INPUT / BLOCKED (B02)`。
- GitHub PR、分支保护和远端 CI：`NOT_RUN`；任务分支推送已完成，但未创建 PR 或修改保护规则。
- Java 17 是本机实际版本；Spring Boot 4.1.1 官方要求至少 Java 17。Java 21 尚未安装，不能写成已验证环境。
- 本机 Node 为 26.0.0 Current；工程目标应使用 Node 24 LTS，Node 26 仅作为本机兼容性检查环境。

## 本轮实际验证

- `python -m unittest discover -s tools/tests -v`：通过，15 个测试通过。
- `python tools/validate_package.py`：通过；规划资产有效，产品验收仍为 `NOT_EXECUTED`。
- `python tools/p0_health_check.py`：命令退出码 0；规划/契约检查通过，但 P0 输入检查仍为 `INPUTS_MISSING`，产品验收仍为 `NOT_EXECUTED`，应用状态仍为 `NOT_IMPLEMENTED`。
- `apps/web`：`npm run lint` 与 `npm run build` 均通过，仅证明模板工程可构建。
- `apps/server`：`mvn test -q` 已执行但失败，Spring 上下文因未配置 `spring.datasource.url` 且尚未实现数据库配置而无法创建 DataSource；这不是业务功能通过证据。

## 完成定义（本轮工程底座）

只有在对应证据归档后，才可把本轮目标标为完成：前后端构建通过、后端测试通过、空 PostgreSQL 可执行 Flyway 迁移、数据库正常/故障时 readiness 行为可复现、前端读取真实接口、秘密扫描无发现、CI 文件可静态检查。远端 CI、分支保护和 PR 必须有远端证据后才能记录为已生效。

## 下一任务

完成本轮工程底座实现和测试；下一批再进入身份与项目权限最小切片，以及登录后创建需求的真实业务流程。M02 不因脚手架存在而整体完成，M03 仍保持 `PLANNED`。

详细命令、退出码和证据位置见 [`docs/progress/runs/R01-STATUS-001.md`](progress/runs/R01-STATUS-001.md)。

# R04-M07-001-FIX03 当前状态

本轮继续 `feat/r04-m07-001` / PR #4（base `feat/r03-m03-002`），起始本地提交为 `a143fc8`。代码修正最终本地提交为 `159c9f2`，GitHub Data API 对应远端代码提交为 `f2aba329069e7152116ee2724afcc5bab6cce5e4`；前端回归提交为 `77320823ff2990d99be9a644571584f75f8c0996`。最终文档交付 SHA 另行记录，不能把本地 SHA 写成远端 CI SHA。

本轮已实现：

- V9 增加 `replay_compatible`，V7/V8 旧哈希记录在升级后由实际 `RequirementService` / 受限 runtime 路径安全拒绝为 `409 IDEMPOTENCY_LEGACY_UNSUPPORTED`，不从当前需求行拼装旧响应；当前格式新幂等请求保留冻结快照重放，并补齐 runtime 列级更新权限。
- `RequirementMigrationUpgradeIT` 使用真实旧 NUL 分隔 SHA-256、两版需求和未过期幂等记录，手工 V7→V9 后调用 runtime service；`RequirementDatabaseIT` 保留撤权与无副作用保护，并将省略字段/`"<null>"` 哈希回归固定在同一 ETag/key 上。
- `RequirementsPanel` 在创建中锁定草稿字段，写操作使被替代读轮次失效，成功/失败/AbortError 结束后恢复可重试状态；新增 `userEvent` 真实输入、迟到详情失败和同键重试测试。前端全套 83 tests 通过。
- 迁移失败探针和 CI 夹具从“正式 V8/故意 V9”改为“正式 V9/故意 V10”，报告门禁使用新的升级测试名；ADR-016 明确旧响应不作无损兼容承诺。

本机已实际验证：系统 Maven `mvn -B -ntp test` 21 tests 通过；`mvn -B -ntp -DskipTests test-compile` 退出 0；前端 `npm ci`（126 packages）、Vitest 6 files/83 tests、lint（0 errors，3 个既有 warning）、Vite build 通过；Python 工具 52 tests 通过；规划校验退出码 0 但仍只代表规划资产健康。Testcontainers/Failsafe 在 Windows 因 JNA `jnidispatch.dll` 临时文件权限和 Docker named pipe 失败；隔离 Ubuntu 的最终 Push/PR CI 已对 `f2aba329…`（PR merge-ref `6fa5cab…`）完成真实 PostgreSQL、HTTP 和浏览器验证。Wrapper 与系统 Maven 结果继续分开记录。

本轮五项重点结果：创建草稿不丢失、读写交接、哈希回归和真实 V7→V9 runtime 处理均由最终 Push/PR CI PASS；前两项本机组件证据也 PASS，后两项本机仍受 Docker/JNA 阻塞。初始失败运行的真实原因已修复并保留。FIX02 的 I01—I10 历史证据保留，I01/I08 受本轮修改的部分已按最终 CI 测试名和实际 checkout SHA 重新核对。不能将 M07 或完整 R04 标为完成。

详细命令、退出码、五项结果和 I01—I10 映射见 [`docs/progress/runs/R04-M07-001-FIX03.md`](progress/runs/R04-M07-001-FIX03.md)。
# R04-M07-001-FIX02 当前状态

本轮继续 `feat/r04-m07-001` / PR #4（base `feat/r03-m03-002`），起始本地提交为 `da5d473aba2daa791184b39e5ed22eb0c82432e6`，审核远端 head 为 `4ed4d3f4eab15392bebf702496d381687e44b76b`；Windows `git fetch origin --prune` 仍受 Schannel `SEC_E_NO_CREDENTIALS (0x8009030E)` 阻塞，未 reset、回退或强推。工作区实现提交为 `3336537ee7e146b381cd4790b6a13b7b7dc2e80d`，GitHub Data API 对应代码提交为 `3e6055a9c1f33dbca34e84c977fb0ad1a19a9df2`，进度文档提交另行记录。

本轮完成：

- 需求前端对 `FORBIDDEN`、`NOT_FOUND`、`UNAUTHENTICATED`、`IDENTITY_DISABLED` 的真实错误清理，保留 HTTP status 与业务 code；失权会清除列表、详情、历史、草稿和写入口并使在途请求失效；CSRF/状态码型 403 与项目范围失权分开处理。
- 修复 A→B、A→空→B 切换中的 saving/loading 轮次，网络错误保留创建草稿和原幂等键，已知重复结果不追加重复行，412 草稿继续保留；前端新增 transport 与竞态回归。
- `RequirementService.parseIfMatch` 与契约统一为单个带引号的强数字 ETag；真实 OIDC HTTP 集成矩阵覆盖 MEMBER/VIEWER/匿名/未入项目、CSRF、If-Match、输入边界、撤权/停用及无副作用；需求端点先接收 JSON 树并拒绝未知字段和非字符串字段。
- `RequirementDatabaseIT` 增加同键/不同键并发创建、精确 412/409、编辑 audit/outbox 回滚；独立 `RequirementMigrationUpgradeIT` 验证 V7 数据升级到 V8、冻结快照和重复迁移；报告门禁要求十二个数据库用例及一个升级用例。

本轮本机验证：前端 `npm ci`（126 packages）、Vitest 6 文件/77 测试、lint（0 error，3 个既有 React warning）、Vite build 均通过；系统 Maven `mvn -B -ntp test` 21 tests 通过；Python 工具 52 tests 通过。真实 Testcontainers/Failsafe 在 Windows 因 JNA `jnidispatch.dll` 临时文件/ Docker named pipe 权限退出码 1，Maven Wrapper 独立退出码 1（PowerShell `icm : Cannot index into a null array`）；均未混写成通过。Push [36968392677](https://github.com/fangzhiy/Test365Alm/actions/runs/36968392677) 六个 job 成功，Push checkout 为 `3e6055a9c1f33dbca34e84c977fb0ad1a19a9df2`；PR [36968396770](https://github.com/fangzhiy/Test365Alm/actions/runs/36968396770) 六个 job 成功，PR merge-ref checkout 为 `6a04165d3cee6c78298643f9a905a9ad8df2bb82`。server 报告 unit 21、OIDC 12、project access 12、requirement 12、upgrade 1，均 failures/errors/skipped=0；browser 8/0/0/0。

本轮 I01–I10：I01 PASS（V7→V8 升级）；I02 PASS（HTTP/数据库创建）；I03 PASS（不可变历史）；I04 PASS（精确并发 412）；I05 PASS（真实 HTTP 12 用例）；I06 PASS（RLS/runtime 隔离）；I07 PASS（撤权/停用原会话直接请求）；I08 PASS（并发幂等）；I09 PASS（回滚与 runtime）；I10 PASS（浏览器 8 用例及 Push/PR CI）。本机 Testcontainers 阻塞已由隔离 Ubuntu CI 覆盖；不标记完整 M07、R04 或生产部署完成。完整结论见 [`docs/progress/runs/R04-M07-001-FIX02.md`](progress/runs/R04-M07-001-FIX02.md)。

