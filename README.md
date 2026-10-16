# Test365Alm
企业级应用质量管理平台研发规划基线。对标原 ALM/Quality Center 产品体系；具体目标版本、Edition、扩展和旧生态兼容范围须在 P0 冻结。

## R11-FIX01：资产边界修正（代码CI通过，待独立审核）

继续[PR #10](https://github.com/fangzhiy/Test365Alm/pull/10)，依赖未合并PR9。原c150958交付的完整成功证据保留；新增回归c3c1d13已在真实CI复现allocator/current回退及感染同key结果变化。最新范围/验收见[FIX01记录](docs/progress/runs/R11-CORE-BATCH04-FIX01.md)。正式迁移追加V21，故意失败探针V22；下文旧V20/V21仅为原轮次历史，不改写旧迁移或清库。

代码验证`561062ba15a9e2695d1c26e66d84fe91b316bf11`的[Push38958095983](https://github.com/fangzhiy/Test365Alm/actions/runs/38958095983)/[PR38958100160](https://github.com/fangzhiy/Test365Alm/actions/runs/38958100160)六Job全部success，PR实际checkout/build为`b659b9ee9b8c52e086cad8fba55122a2aaeab1c4`。66单元、152真实集成（M05精确27，整个OIDC24中M05仅3）、213前端、123Python、完整浏览器14+独立UI1，零失败/错误/跳过；非空V20→V21及正式SCRAM scanner LOGIN真实通过。三次候选失败及门禁自修保留。最终文档交付自己的CI在交付回复核对，不循环追写文档；历史/me401未复现，根因仍未证明。

已有数据库升级后的scanner账号由正式`python tools/provision_asset_scanner_role.py`提供。先由授权operator完成备份/评估及Flyway升级，再在受控终端从秘密管理渠道设置`PGHOST`、`PGPORT`、`PGDATABASE`、`PGUSER`与operator的`PGPASSWORD`或`PGPASSFILE`/TLS配置，并设置新独立`TEST365ALM_ASSETS_SCANNER_DB_USERNAME`、`TEST365ALM_ASSETS_SCANNER_DB_PASSWORD`；密码须24..256个非空格ASCII字符。不要将真实值写进命令参数、聊天、日志或仓库，不启用shell trace。

运行该工具只创建受限scanner LOGIN子角色并验证登录，不运行Flyway、不改开发`.env`、不修改全局数据库配置。`test365alm_scanner`是能力group，已有fresh环境的group LOGIN保持不变；应用用户名可以是独立子角色。安全重复调用不旋转秘密、不扩大权限；不安全角色或前置条件拒绝时，先检查具体授权/角色配置，不改用owner/runtime账号让应用继续。应用始终显式读取scanner用户名/密码，固定使用应用同一数据库URL。新增secret需要由operator以既有安全方式更新应用本地配置，工具不会代写。

正常运维默认调用本机`psql -X -w`；只有显式给出`TEST365ALM_PROVISION_CONTAINER_ID`完整ID时才可使用指定已授权容器的psql。此模式仅支持容器内`127.0.0.1:5432`，不会自动发现、创建、停止或清理容器。集成测试通过Testcontainers证明资源身份后使用此正式入口；不要把用户日常数据库当作测试资源。

正式工具使用兼容PostgreSQL17 `require_auth`的psql/libpq，并在operator与scanner两次连接中都明确要求SCRAM-SHA-256认证。不支持该参数、trust或未完成SCRAM握手时安全拒绝；“连接成功”不能替代密码有效性。工具不会自动改`pg_hba.conf`、放宽权限或切换认证；若现有授权连接策略不满足，先由负责人评审适用配置，不在生产试错。容器内偏转环境变量必须真正unset，空字符串不等同移除。

工具要求operator既有角色管理授权，也能设置所需会话日志参数；只有`SET LOCAL`保护全部成功后才发送SCRAM verifier（它也属于秘密）。存在preload扩展日志钩子或无法保证该会话日志边界时安全拒绝，不修改全局日志、不要求放宽生产权限。授权operator需评估自己的环境与审计政策；本轮仅验证隔离PostgreSQL。若创建已提交但随后登录核验失败，工具返回非零并明确可能部分完成；先核对已创建角色，不能自动重置密码、扩大权限或用owner替代。

发布感染结果后，原complete key在有效期内重放同一422/FILE_INFECTED；新key对REJECTED仍409/UPLOAD_REJECTED。scanner暂时失败的503不永久冻结，恢复后原key可明确重试。所有重放先重新授权。资产current只能是最大修订，allocator在提交时严格连续；旧附件仍冻结旧修订。以下原R11页面及完整测试命令继续适用。

> **原R11历史基线：资产附件最小切片代码CI通过。** 代码 `2a38148bfad5fd9b8e599b6d469a8e8b7601717d` 的Push38948375346/PR38948383146均六Job成功，原交付c150958的Push38949795012/PR38949800659也已成功。这不替代本次FIX01；当前结果以顶部FIX01记录为准。默认分支仍 `chore/r01-status-001@a2419e292c004b2cd4c7278bdaa40b1ce3442a22`，PR #1～#8已合入、PR9/10未合并。下文旧轮次的范围、未实现项和迁移版本为历史，不代表完整模块或ALM完成。

## R11 私有资产与冻结附件（最小切片，代码验证通过）

本轮仅M05第一切片；代码CI实际63单元、143真实集成、213前端、106 Python、14完整浏览器及独立项目UI另1项，零失败/错误/跳过。起点、确切场景、两次历史红CI及限制见[记录](docs/progress/runs/R11-CORE-BATCH04.md)，规则见[契约](contracts/assets.md)/[ADR025](docs/adr/025-r11-private-assets-and-frozen-attachments.md)。R10 PR9尚未合并，新功能不写入主线。历史孤立登录间歇失败根因未证明，增加安全诊断并保留原测试，不宣称已根治。

全新隔离开发环境：根目录执行`python tools/prepare_r03_dev.py`，它生成随机本地凭据并拒绝覆盖已有`.env.r03`/realm。使用唯一项目名，运行`docker compose --env-file .env.r03 -f compose.r03.yaml -f compose.r11.yaml -p <唯一项目名> up -d postgres keycloak asset-store asset-scanner`。新PostgreSQL容器初始化独立runtime/scanner账号，Flyway由migrator执行当前V1—V21；S3两个私有bucket、clamd都只发布回环端口。不要对已有数据库卷重新初始化或清空。

已有`.env.r03`时不删除/覆盖它：按`prepare_r03_dev.py`列出的R11键在受控独立环境自行配置对象服务/扫描账号；旧数据库必须先备份评估并由授权迁移身份升级到V21，再按顶部正式工具说明单独配置scanner LOGIN。不要把migrator/owner凭据填入scanner或runtime。应用scanner连接固定使用同一个datasource URL；`TEST365ALM_ASSETS_ENABLED`默认false，未配置不提供伪成功上传。

PowerShell分别启动后端/前端：` . .\tools\import_r03_env.ps1`加载同一份配置，在`apps/server`运行`.\mvnw.cmd spring-boot:run`；在`apps/web`运行`npm run dev`。可先运行`python tools/verify_r11_services.py`检查本机私有S3拒绝匿名访问及真实clamd benign控制。应用健康探针仍只说明平台/数据库状态，不声称外部文件服务全部正常。

普通MEMBER进入需求、用例或缺陷详情的“附件”区域：选择允许类型文件→创建会话→上传→扫描发布→关联冻结版本→下载。可查看资产/上传新版本，旧关联仍下载原修订；VIEWER只能读取/下载。未知结果使用原意图重试或“读取上传状态/恢复本人上传会话”，不要换键重建同一上传。

完整验证：根目录`python -m unittest discover -s tools/tests -v`和`python tools/validate_package.py`；后端`mvn -B -ntp -Pintegration verify`（Windows Wrapper独立`.\mvnw.cmd`，Linux`./mvnw`）。Testcontainers自行创建真实临时PG/S3/clamd，不读取开发数据源。执行根目录`python tools/verify_r11_asset_report.py apps/server/target/failsafe-reports`。前端沿用下方JSON测试命令，再运行`python tools/verify_r11_asset_web_report.py local-evidence/r10/vitest.json`。浏览器`npm run test:e2e`只在CI-owned归属资源中执行；保留全部原用例、单worker、零重试。

停止本机环境用同一Compose文件/项目名`stop`，不删除卷。CI只经既有resource-manifest归属校验清理本轮资源，不使用项目级强制清卷。S3/scanner不可用时保持隔离/安全失败；扫描失败可明确重试，感染拒绝为终态；cleanupPending不是下载授权漏洞，完整GC仍未实现。无生产部署、免费外部共享存储或客户数据配置。

## R10 发布、周期与冻结质量快照（历史）

沿用下文已有本机隔离启动，不覆盖 `.env.r03`。PROJECT_ADMIN 登录并选项目，在“发布与周期工作台”创建发布和日期，打开详情创建日期包含于发布的周期；从真实需求/用例候选加入范围，显示的是已冻结 revision。新增源修订不会自动改变该范围。

点击“生成质量快照”，查看分子/分母、规则和范围指纹。在原手工执行工作台完成该冻结用例后再评估，新的快照反映执行事实，历史快照保持原值。活动重跑不会把旧 PASS 算当前 PASS。MEMBER/VIEWER 可查看发布、周期及快照，但无写入权限；未入项目的租户管理员无隐式访问。

发布/周期编辑使用版本条件；412 保留草稿，读取最新后必须明确采用或放弃。未知写入保留原键/内容/版本重试；写已确认后仅刷新 GET。归档后全部新写入拒绝，历史仍可读。列表通过“更多”继续分页，不下载全部页；范围最多1000、相关缺陷最多10000，超限明确拒绝评估，不显示截断指标。

R10当轮正式迁移 V19、专用失败探针 V20；现行R11-FIX01为正式V21/探针V22。完整命令沿用下方 `mvn -B -ntp -Pintegration verify`，专项证据检查为 `python tools/verify_r10_release_report.py apps/server/target/failsafe-reports`；浏览器为 `npx playwright test e2e/releases.spec.ts --workers=1 --retries=0`，仅对本轮 CI-owned 环境执行。契约见 [releases](contracts/releases.md)、[ADR024](docs/adr/024-r10-release-cycle-quality-snapshot.md)、[R10实际记录](docs/progress/runs/R10-CORE-BATCH03.md)。不是完整规划、质量中心、审批或资产管理模块。

前端在`apps/web`执行 `npm run test:run -- --reporter=verbose --reporter=json --outputFile.json=../../local-evidence/r10/vitest.json` 后，在根目录执行 `python tools/verify_r10_release_web_report.py local-evidence/r10/vitest.json`，验证实际组件/API测试名称与完整结果。CI保存`frontend-evidence`内JSON/摘要，以及`backend-evidence`内Failsafe XML/`release-r10-report.txt`；浏览器仅上传脱敏事件时间线与JUnit，不上传原始认证trace。

## R09 缺陷与失败步骤复测（历史切片）

沿用下文 R03 本机隔离启动方式，不覆盖已有 `.env.r03`。登录普通 PROJECT_MEMBER 并选择项目：

1. 用现有需求/用例页面创建数据，可建立需求→用例追踪；在手工运行中保存 FAIL/BLOCKED 步骤并完成尝试。
2. 点击失败步骤的“从失败步骤创建缺陷”，填写纯文本标题/描述、严重度/优先级。服务端冻结真实 test/revision/run/attempt/step、原实际/预期和有效需求关系，不能手填派生来源。
3. 在“缺陷工作台”刷新、查询或继续游标分页，打开 BUG 编号。编辑追加修订；转为 IN_PROGRESS→RESOLVED，填写修复说明。无关或旧周期 PASS 不允许关闭来源缺陷。
4. 从缺陷详情“开始复测”，通过原执行服务保存复测步骤 PASS 并完成；“刷新缺陷详情”重新读取验证，合格后转为 CLOSED。
5. 真实重载页面并重新选择项目，打开原失败尝试、复测 PASS 与各状态历史；旧 FAIL 永不覆盖。独立 VIEWER 只能读取，不能写入。普通非执行来源缺陷可新建，RESOLVED有说明后按有限契约关闭。

请求超时或网络断开时，未知写意图锁定原内容/版本/幂等键，用“重试原意图”确认；不要换新键重复创建。412 保留旧草稿及基础版本；读取最新后明确采用或放弃，不自动重发覆盖。已确认写成功后读取失败，仅刷新 GET。项目/身份/读取权限失效清理原范围。附件/通知/自定义工作流/SLA/复杂树/同步/AI/完整报表未实现。

契约：[缺陷](contracts/defects.md)；决策：[ADR023](docs/adr/023-r09-defect-core-and-related-retest.md)；输入与实际失败/验证：[R09记录](docs/progress/runs/R09-CORE-BATCH02.md)。R09当时正式迁移V18、专用失败探针V19；下面R08/R06段落中的版本也是历史切片说明。

```powershell
cd apps/server
mvn -B -ntp test
# 独立 Windows Wrapper 调用：.\mvnw.cmd -B -ntp test
# 真实数据库（需本机Docker）：mvn -B -ntp -Pintegration verify
# Linux CI：./mvnw -B -ntp -Pintegration verify
cd ../..
python tools/verify_r09_defect_report.py apps/server/target/failsafe-reports
python -m unittest discover -s tools/tests -v
python tools/validate_package.py
cd apps/web
npm ci
npm run lint
npm run test:run
npm run build
# 真实页面须本轮CI-owned PostgreSQL/Keycloak及三用户，不可指向日常开发数据库：
# npx playwright test e2e/defects.spec.ts --workers=1 --retries=0
```

缺少 Docker 时真实集成不得视为通过；本轮Ubuntu六Job CI包含精确名称门禁、实际OIDC/Keycloak及安全资源清理。不改全局权限/默认工具链、不部署。报告目录/数量与实际checkout以轮次记录为准；规划校验不是业务验收。

## R08 字段元数据与需求测试追踪

从现有本机隔离开发服务启动（下文 R03 启动说明不变），登录并选择项目：

1. PROJECT_ADMIN 在“项目字段元数据”定义 TEXT/NUMBER/ENUM、必填/默认/约束，保存后选择字段并调用真实服务器验证值。修订是追加历史，key/type 不变；VIEWER 没有定义写入口。
2. 普通 PROJECT_MEMBER 通过现有需求与手工用例页面创建对象，在需求详情“需求→测试追踪”选择本项目用例并建立关系。
3. 点击“刷新覆盖统计”：未执行用例的 PASS 为 0；完成该绑定修订的手工运行 PASS 后再刷新可观察同步覆盖。存在活动重跑、需求/用例修订变化时不把旧 PASS 当当前通过。
4. 关联失效使用明确的带版本写操作，不物理删除历史。结果未知时锁定原快照，点击“重试原意图”沿用原幂等键，不能通过刷新自动重复创建。

本轮不存储业务自定义字段值、不实施动态表单设计器、导入/同步、suspect、watermark 或缺陷关系。详细语义见 [字段契约](contracts/field-metadata.md)、[追踪契约](contracts/requirement-test-trace.md) 和 [ADR 022](docs/adr/022-r08-field-metadata-and-typed-trace.md)。

```powershell
cd apps/server
mvn -B -ntp -Pintegration verify
# Windows Wrapper 是独立调用方式：.\mvnw.cmd -B -ntp -Pintegration verify
# Linux：./mvnw -B -ntp -Pintegration verify
cd ../..
python tools/verify_r08_core_report.py apps/server/target/failsafe-reports
python -m unittest discover -s tools/tests -v
python tools/validate_package.py
cd apps/web
npm ci
npm run lint
npm run test:run
npm run build
```

真实数据库测试使用 Testcontainers 独占资源；不读取开发 `.env`，owner 只负责迁移/夹具/核验，业务使用受限 runtime。正式 V17 新增本切片表与 RLS，V1—V16 不变；独立失败迁移探针现为 V18。原 V13/V14→V16 专项继续执行，新增非空 V16→V17 数据/历史/幂等保留测试。完整六 Job CI 还执行原停库恢复及单 worker、零重试 Keycloak 浏览器流程。

当前开发状态以 [development-status.md](docs/development-status.md) 和最新轮次记录为准；R03/R02 历史记录保留供追溯。

## R06 M09 手工执行切片

R07 专项使用两个独立的 Testcontainers PostgreSQL 17.11，分别在真实 V13、V14 结构播种非空历史，再升级至 V16；不读取日常 `.env`。先运行专项，再完整回归（需要本机 Docker）：

```powershell
cd apps/server
.\mvnw.cmd -B -ntp -Pintegration verify '-Dit.test=ManualExecutionMigrationUpgradeIT'
.\mvnw.cmd -B -ntp -Pintegration verify
cd ../..
python tools/verify_r07_execution_upgrade_report.py apps/server/target/failsafe-reports
```

Linux 对应 `./mvnw`。两个必需方法为 `v13NonEmptyExecutionDatabaseUpgradesToV16` 和 `v14NonEmptyExecutionDatabaseUpgradesToV16`；缺失、重复、失败、错误、跳过或报告不一致均阻断。旧 V13 幂等记录没有冻结响应时返回 `409/IDEMPOTENCY_RESPONSE_UNAVAILABLE`，不是旧响应无损兼容。详细范围与实际证据见 [本轮记录](docs/progress/runs/R07-INT-001-FIX01.md)。

已登录并选择项目的普通 MEMBER 可创建测试集、选择已保存手工用例修订加入实例、启动运行、保存步骤、暂停/继续、完成及重跑。重跑创建新尝试；历史步骤读取固定 manifest，不跟随当前用例变化。VIEWER 可读不能写。列表按游标继续加载；测试集汇总的分母是该范围全部实例，未运行指从未创建 run 的实例；活动尝试与每个实例最新已完成结果分开展示。

演示：创建含两步的用例 → 创建测试集并加入该修订 → 启动运行并逐步保存 → 暂停/继续 → 完成 FAIL → 重跑 PASS → 重载页面重新选择范围 → 读取第一次 FAIL 历史。412 保留草稿和基础版本，必须明确采用最新状态后才能继续编辑；未知写入复用原键，读取恢复不重发写入。尝试历史页只有摘要，步骤来自当前运行详情或所选尝试的准确详情接口。

验证：`cd apps/server` 后运行 `mvn -B -ntp -Pintegration verify`（Linux CI 使用 `./mvnw`）；根目录运行 `python tools/verify_r06_manual_execution_report.py apps/server/target/failsafe-reports`。前端命令沿用下文，CI 的十个浏览器用例固定单 worker、零重试，并保存脱敏 M09 步骤时间线。完整 M09 的附件、缺陷联动、更正审批、导出等仍未实现，不能以本切片通过替代模块全量验收。

## R05-M08-001 手工测试用例第一切片

当前切片在已选项目中提供 MANUAL 测试用例列表、创建、详情、纯文本标题/说明/前置条件、步骤新增/移除/调序、不可变修订历史和项目角色边界。`PROJECT_ADMIN`/`PROJECT_MEMBER` 可读写，`PROJECT_VIEWER` 只读；服务端生成稳定步骤键和项目内显示编号，追加修订使用强 ETag 与持久化幂等键。V11 在完整步骤快照写入后封存修订；V10 既有数据升级时以创建时间作为封存证据，运行时继续使用受限 PostgreSQL 账户，历史修订/步骤不允许更新、删除或清空，也不能向已保存修订追加步骤。

本轮明确不包含测试树、复制、配置/参数、被调用测试、附件、执行、结果、需求关联或完整 M08。接口字段与错误约束见 [test-cases.json](contracts/test-cases.json)，设计决定见 [ADR-017](docs/adr/017-r05-manual-test-case-slice.md) 和 [ADR-018](docs/adr/018-r05-sealed-test-steps.md)。

真实 M08 PostgreSQL 集成验证使用隔离 Testcontainers，不读取日常 `.env`；V11 封存边界和 V9→V11 升级用例由报告门禁强制发现：

```powershell
Set-Location apps/server
mvn -B -ntp test
mvn -B -ntp -Pintegration verify '-Dbuild.commit=local-r05-m08-001'
python ..\..\tools\verify_r05_test_case_report.py target\failsafe-reports
python ..\..\tools\verify_r03_oidc_http_report.py target\failsafe-reports
```

Linux CI 使用 `./mvnw`；Windows 本机应分别记录 `mvnw.cmd` 的实际退出码和系统 Maven 结果，不能混写。Docker/Testcontainers 不可用时，集成检查必须记录 `BLOCKED/NOT_RUN`，不能用单元测试替代真实 PostgreSQL 证据。前端工作台验证：

```powershell
Set-Location apps/web
npm ci
npm run lint
npm run test:run
npm run build
```

## R03-M03-002 项目访问最小切片

B 段在已认证的 BFF 会话下提供租户、项目、成员和固定角色的最小管理闭环。工作台的“租户、项目与成员”面板按需读取当前主体可见范围，可创建项目、保存 `PROJECT_ADMIN`、`PROJECT_MEMBER`、`PROJECT_VIEWER` 固定项目角色并撤销成员访问；租户、域和初始成员只允许通过隔离的开发/测试 bootstrap 准备，本轮不开放租户/域管理 HTTP 接口。前端不持有 OIDC token，也不以隐藏页面代替服务端授权。接口字段、错误体和撤权语义见 [project-access.json](contracts/project-access.json)，架构边界见 [ADR 012](docs/adr/012-r03-project-access-slice.md)。

该切片已包含新租户/项目表的基础 RLS 和追加审计证据，但仍不代表完整 M03：跨节点会话、MFA、完整审计策略、批量目录和旧 ALM 兼容继续按开发状态记录验证。

## R04-M07-001 需求第一条业务闭环

R04 在现有登录和项目上下文之上增加了受项目成员授权的需求最小闭环：列表、创建、详情、带 `If-Match` 的编辑，以及只读不可变修订历史。`PROJECT_ADMIN` 与 `PROJECT_MEMBER` 具有 `requirement:create`/`requirement:update`，`PROJECT_VIEWER` 只能读取需求和历史；租户成员但未加入项目的主体不能读取。创建和编辑使用真实 PostgreSQL 的 V7→V9 受控迁移，稳定需求身份、项目内显示编号和修订身份分离，编号通过行锁分配，审计、Outbox 与幂等记录在同一事务中写入。

当前实现不包含父子树、富文本、附件、评审、追踪、删除、导入导出或 AI；完整 M07 仍按开发状态文档保持未完成。实际接口字段和错误/ETag 约束见 [requirements.json](contracts/requirements.json)，架构决定见 [ADR-013](docs/adr/013-r04-requirement-revision-slice.md)。前端从已选项目上下文读取需求，不接受手工 tenant/project ID；412 冲突会保留草稿，退出或切换项目会清理旧数据。

后端单元测试：

```powershell
Set-Location apps/server
mvn -B -ntp test
```

真实 PostgreSQL 集成测试使用 Testcontainers 的一次性数据库，不读取日常 `.env`。`RequirementDatabaseIT` 覆盖合法成员创建/编辑、不可变历史、viewer runtime 写拒绝、同项目/跨项目/跨租户复合引用、幂等快照/过期/撤权、同键和不同键并发创建、字段哈希边界、创建及编辑审计/Outbox 故障回滚和同 ETag 并发竞争；独立 `RequirementMigrationUpgradeIT` 构造真实 V7 旧摘要和多版需求，执行 V7→V9 升级后通过受限 runtime service 验证旧重放安全拒绝、当前格式重放和撤权保护。CI 报告门禁要求数据库用例及一个升级用例全部实际执行：

```powershell
Set-Location apps/server
mvn -B -ntp -Pintegration verify '-Dbuild.commit=local-r04-fix01'
```

本机 Docker/Testcontainers 不可用时，该命令必须如实记录失败原因；不能以单元测试替代真实数据库证据。CI 会执行 `tools/verify_r04_requirement_report.py`，要求全部指定数据库用例和 V7→V9 升级用例均被发现且无失败、错误或跳过。V1—V8 只在受控迁移阶段执行，V9 为旧幂等重放安全边界；运行时不会自动改表，服务使用受限 runtime 数据源。前端继续使用：

```powershell
Set-Location apps/web
npm ci
npm run lint
npm run test:run
npm run build
```

## R03 本地 OIDC 登录切片

本节只用于本机隔离演示。需要 Docker Desktop、Java 21（本机 Java 17 可编译目标 17）、Node 24（本机 Node 26 已实测）、Python 3.10+。`compose.r03.yaml` 固定 PostgreSQL 17.11 和 Keycloak 26.4.4 的镜像摘要；Keycloak `start-dev`、回环 HTTP Cookie 例外和生成的测试账户绝不可直接用于生产。不要连接公司身份系统或日常开发数据库。

在仓库根目录执行：

```powershell
python tools/prepare_r03_dev.py
docker compose --env-file .env.r03 -f compose.r03.yaml -p test365alm-r03-my-run up -d postgres keycloak
curl.exe -f http://127.0.0.1:18090/realms/test365alm/.well-known/openid-configuration
```

准备脚本只在 `.env.r03` 和 `local-evidence/r03/realm.json` 均不存在时创建本地随机凭据；存在时退出且不覆盖。两者被 Git 忽略。项目名应为本轮唯一值；首次启动前确认没有同名 Compose 资源。PostgreSQL 只发布到 `127.0.0.1:54339`，Keycloak 只发布到 `127.0.0.1:18090`。服务容器内监听地址与宿主机发布地址是不同边界。

Linux/CI 使用锁定的 Keycloak 镜像时，realm 导入文件需由容器 UID 1000 读取；生成器在 POSIX 上将它设为仅所有者可读。确认路径是本轮生成的临时文件后执行 `sudo chown 1000:1000 local-evidence/r03/realm.json` 与 `sudo chmod 0400 local-evidence/r03/realm.json`，不开放整个工作区权限，也不要把 realm 或 `.env.r03` 上传为制品。CI 在启动前检查文件权限、镜像用户和资源项目归属，失败时保存脱敏容器日志。

后端终端（PowerShell）：

```powershell
Set-Location apps/server
. ..\..\tools\import_r03_env.ps1 -Path ..\..\.env.r03
.\mvnw.cmd spring-boot:run
```

若此工作站的 Windows Maven Wrapper 启动报 `Cannot index into a null array`，可临时使用已安装的 Maven 3.9.14 执行 `mvn spring-boot:run`；Linux CI 使用可执行的 `./mvnw`。Flyway 从 `.env.r03` 的 `SPRING_FLYWAY_*` 使用迁移账号，运行时 `TEST365ALM_DATASOURCE_*` 使用非 owner、无 DDL 的独立账号。默认后端仅绑定 `127.0.0.1:8080`；如端口被占用，可在启动前设置 `TEST365ALM_SERVER_PORT=18083`，并在前端设置相同目标。不要终止占用端口的未知进程。

前端终端：

```powershell
Set-Location apps/web
npm ci
$env:TEST365ALM_DEV_BACKEND_URL='http://127.0.0.1:8080'
npm run dev
```

浏览器访问 <http://127.0.0.1:5173>，点击“登录 Test365Alm”。隔离测试用户为 `r03-user`，其随机密码仅在被忽略的 `.env.r03` 的 `R03_TEST_USER_PASSWORD` 中。Keycloak 授权回调固定为前端代理 `http://127.0.0.1:5173/login/oauth2/code/test365alm`；不要将后端端口直接当作回调 URI。登录后 `/api/v1/me` 只返回本地稳定身份，不含 token 或项目权限。退出只结束本应用会话；重新登录必须显式点击登录链接。会话闲置 30 分钟，后端重启后需重新登录。

检查与停止：

```powershell
curl.exe -i http://127.0.0.1:8080/health/ready
curl.exe -i http://127.0.0.1:8080/api/v1/me
docker compose --env-file .env.r03 -f compose.r03.yaml -p test365alm-r03-my-run ps
python tools/cleanup_r02_resources.py --root local-evidence/r03
```

匿名 `/api/v1/me` 应返回 JSON 401；`/health/ready` 应返回 200。清理只读取本轮 manifest，并在 Docker context、Engine、项目和 run 标签均匹配时删除记录资源；没有 manifest 或归属无法确认时返回非零，不能改用项目级 `down`。改变 Keycloak、PostgreSQL 或前端端口时，必须同步更新 `.env.r03` 中的 JDBC/issuer、realm import 中的回调地址和前端代理；不要只改 Compose 发布端口。浏览器若显示登录失败，先检查 Keycloak discovery、固定回调、后端 OIDC profile 和本地角色迁移；不要关闭 issuer、签名、CSRF 校验。生产必须采用 HTTPS 与 Secure Cookie、正式 IdP、独立秘密管理和独立安全评审。

自动化回归：后端 `cd apps/server; .\mvnw.cmd -B -ntp -Pintegration verify` 使用 Testcontainers 的临时 PostgreSQL，并运行真实 HTTP OIDC 回调集成类 `OidcCallbackSecurityIT`（10 个受门禁用例，含运行时账号、项目 HTTP 和未 bootstrap 拒绝场景）；可单独复跑 `.\mvnw.cmd -B -ntp -Pintegration verify '-Dit.test=OidcCallbackSecurityIT'`。前端 `cd apps/web; npm ci; npm run lint; npm run test:run; npm run build`。真实浏览器联调需先启动上述四个本地服务，再在 `apps/web` 执行 `. ..\..\tools\import_r03_env.ps1 -Path ..\..\.env.r03; npm run test:e2e`；本机可设置 `R03_E2E_BROWSER_CHANNEL=chrome` 使用已安装 Chrome，CI 安装隔离 Chromium。E2E 不用 mock 登录取代真实 Keycloak。

FIX01 的项目访问/手工测试用例演示以及 R04 需求演示只在 CI 本轮拥有的隔离 Compose 项目中运行：项目授权用例保留管理员与 viewer，手工测试用例用三个真实 Keycloak 用户（管理员、普通 `PROJECT_MEMBER`、独立 viewer）建立本地 principal，由迁移账号在同一临时数据库播种 tenant/domain/tenant_member，然后通过真实浏览器会话完成项目授权和“普通成员创建用例 → 刷新重读 → 编辑调序 → 保存新修订 → 查看旧修订；viewer 只读”。`tools/verify_r03_project_browser_report.py` 要求项目授权、项目 UI 和需求 UI 用例实际执行且无失败/错误/跳过；普通本机运行因资源归属门禁会跳过并返回非成功报告，不得把它当作真实项目验收通过。生成器不会覆盖已有 `.env.r03` 或 realm；若本地旧配置只有单用户，先确认归属后按隔离环境规则重新生成。

会话到期、主体停用和 IdP 中断用例会改变测试资源状态，仅在带本轮 `R03_RUN_ID` 标签的唯一 CI Compose 项目中运行；普通本机开发只运行不破坏数据的浏览器用例。CI 将空闲超时配置为 1 分钟，测试关闭页面避免轮询续期，等待 75 秒后检查旧 Cookie。IdP 不可用时，新登录必须失败；已建立的本地会话在本地有效期内仍可访问、可 CSRF 退出。这里不承诺实时 IdP 撤权或全局单点退出。

## R02 工程底座：本地启动

本节只覆盖开发环境 PostgreSQL 和 M02 最小运行闭环，不部署生产，也不连接生产数据。目标工具链为 Java 21、Node 24 LTS；若本机版本不同，先以轮次记录中的实际验证结果为准。

### 前置条件

- Docker Desktop（Compose v2）
- Java 21 和 Maven Wrapper（Windows 使用 `mvnw.cmd`，Linux/macOS 使用 `./mvnw`）
- Node 24 LTS 与 npm
- Python 3.10+

### 启动

PowerShell：

```powershell
if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
. .\tools\import_dev_env.ps1
docker compose --env-file .env -p test365alm-r02 up -d postgres
Set-Location apps/server
.\mvnw.cmd spring-boot:run
```

如果 `.env` 已存在，上面的命令会保留它，不会覆盖本机配置；`import_dev_env.ps1` 将同一份配置加载给宿主机上的 Spring Boot。修改 `POSTGRES_HOST_PORT` 或凭据后，重新在启动后端的终端执行 `. .\tools\import_dev_env.ps1`，不要在命令行另写一套旧值。

另开终端启动前端：

```powershell
Set-Location apps/web
npm ci
npm run dev
```

浏览器访问 <http://127.0.0.1:5173>。Vite 开发代理只把 `/api` 和 `/health` 转发到本机后端；不配置任意来源跨域。
默认后端地址为 `http://127.0.0.1:8080`；若该端口被本机其他服务占用，可在启动前端前设置 `TEST365ALM_DEV_BACKEND_URL=http://127.0.0.1:18080` 指向本轮隔离后端端口。

### 检查、停止与排障

```powershell
curl.exe -i http://127.0.0.1:8080/health/live
curl.exe -i http://127.0.0.1:8080/health/ready
curl.exe -i http://127.0.0.1:8080/api/v1/version
Get-NetTCPConnection -State Listen -LocalPort 8080 | Select-Object LocalAddress,LocalPort,OwningProcess
docker compose --env-file .env -p test365alm-r02 ps
docker compose --env-file .env -p test365alm-r02 down
```

`down` 默认保留数据库卷；不要对不明确的环境使用 `down -v`。readiness 返回 503 时先检查容器健康状态、数据源环境变量和后端日志。`.env` 仅供本机使用，真实凭据不得提交。

后端的 `server.address` 默认是 `127.0.0.1`，前端 Vite 默认 host 也是 `127.0.0.1`；只有在明确启动容器网络时才通过环境变量覆盖。readiness 的 JDBC 连接校验和结构查询各自使用 `TEST365ALM_READINESS_TIMEOUT_MS` 的有界预算，不能将两段预算相加后理解为单一 HTTP 请求上限。

要复现数据库故障场景，保持后端进程运行，执行 `docker compose --env-file .env -p test365alm-r02 stop postgres`，确认 live 仍为 200 且 ready 为 503；随后执行 `docker compose --env-file .env -p test365alm-r02 start postgres`，ready 应在下一次探测恢复为 200。该手工场景使用开发数据库项目，只允许停/起本机开发容器，不要删除卷。

### 自动化测试与故障注入

后端集成测试默认使用 Testcontainers 创建唯一的临时 PostgreSQL 目标容器；隔离测试还会创建独立的临时对照容器。它不读取日常 `.env`，测试结束由 Testcontainers 清理容器，要求 Docker Desktop/兼容 Docker API 可用：

```powershell
Set-Location apps/server
.\mvnw.cmd -B -ntp -Pintegration verify '-Dbuild.commit=local-r02'
```

故障恢复脚本使用专用 `.env.r02-test`（不会覆盖已有 `.env`），项目名和后端端口应为本轮唯一值；脚本会确认后端 PID、版本提交、Docker context/Engine、Compose 容器/网络/卷的本轮标签和资源 ID，并在数据库端口冲突时选择临时端口：

```powershell
if (-not (Test-Path -LiteralPath .env.r02-test)) { Copy-Item .env.example .env.r02-test }
python tools/verify_r02_readiness.py --env-file .env.r02-test --compose-project test365alm-r02-manual --server-port 18081
```

脚本严格要求监听地址可验证且只能是 `127.0.0.1`、`::1` 或 `::ffff:127.0.0.1`；无法枚举监听、进程提前退出、版本提交不一致、端口被占用或数据源不是本机专用 PostgreSQL 时失败。首次 `up` 前会检查目标项目是否已有容器（包括停止的容器）、网络或卷；任何已有资源都会使脚本拒绝运行。默认使用当前本地 Docker context；若专用测试配置写入 `TEST365ALM_DOCKER_CONTEXT`，它必须与当前 context 一致。远端 Docker context 被拒绝。测试配置只接受脚本规定的键；父进程的 Spring、JVM 和 Compose 覆盖项不会传入子进程。

启动迁移失败验证保留正式 V21，向临时目录注入独立的 V22 故障迁移，不修改正式 `db/migration`；脚本先确认正式 V21 已成功记录：

```powershell
python tools/verify_r02_migration_failure.py --env-file .env.r02-test --compose-project test365alm-r02-migration-manual --server-port 18082
```

使用一次性对照项目验证两套脚本不会碰触预先存在的容器、卷和两条哨兵数据：

```powershell
python tools/verify_r02_preexisting_project.py --env-file .env.r02-test
```

脚本成功时只清理本轮 manifest 记录且仍带有本轮标签的资源。如果异常中断，可用 `python tools/cleanup_r02_resources.py --root local-evidence/r02-readiness`（迁移脚本用 `local-evidence/r02-migration-failure`，对照脚本用 `local-evidence/r02-control`）重试；资源身份或 Docker Engine 无法确认时会拒绝删除并返回非零码，不要改用项目级 `down --volumes`。

### 可复现验证命令

```bash
python -m unittest discover -s tools/tests -v
python tools/validate_package.py
python tools/p0_health_check.py
cd apps/web && npm ci && npm run lint && npm run test:run && npm run build
cd ../server && ./mvnw -B -ntp test
./mvnw -B -ntp -Pintegration verify -Dbuild.commit=local-r02
python tools/verify_r02_migration_failure.py --env-file .env.r02-test --compose-project test365alm-r02-migration-manual --server-port 18082
```

Windows PowerShell 的 Maven 系统属性建议写成 `'-Dbuild.commit=local-r02'`，避免参数被 shell 解析。Linux 检出后如执行权限未保留，先运行 `chmod +x apps/server/mvnw`。

## 先阅读

| 对象 | 文档 |
|---|---|
| 老板 / 投资决策人 | [项目实施计划书](docs/01-executive-project-plan.md) |
| 开发、架构、测试、运维 | [详细实施方案：主方案和32模块附录](docs/02-technical-implementation.md) |
| 模块负责人 | [模块目录](docs/modules/) |
| QA / 实施 | [端到端验证手册](docs/05-end-to-end-validation.md) |
| 管理 / 合规 | [资料来源与假设](docs/03-sources-and-assumptions.md) |
| 发布人员 | [创建私有 GitHub 仓库与上传](docs/04-github-publishing.md) |
| 数据/API 负责人 | [领域模型](contracts/domain-model.md)；[OpenAPI 子集](contracts/openapi-core.json) |
| P0 执行人员 | [P0 执行资料](p0/README.md)；[范围基线草案](p0/scope-baseline.md) |

Word 交付件见 [deliverables](deliverables/)。CSV 为 UTF-8 BOM，便于表格工具打开；JSON 是预算和模块的机器可读输入。

## 项目资产

32 个工作模块、192 个实施工作包、192 条初始工程范围映射、160 条模块验收断言、24 条端到端验证场景、78 个两周迭代安排及 84 条拟定接口条目。它们是初始工程分解，不是原厂官方功能数量。P0 应继续展开到字段、行为、权限和扩展；未确认项不可默认为排除。

预算是规划假设：36 个月、618 人月、4 万元/人月，加环境及外部费用和20%预备费共3354万元；建议首先只审批P0约54万元。尚未得到公司预算批准，也不构成报价。

## 现在可运行的检查

```bash
python tools/validate_package.py
python tools/calculate_budget.py
python -m unittest discover -s tools/tests -v
```

这些只验证文档包结构、预算算术和辅助工具，不会启动 ALM 服务，也不代表业务测试通过。

## R06-M09-001 手工执行切片

在选择项目并登录后，工作台的“测试集与手工运行”区域从真实后端读取测试集、已保存 MANUAL 用例修订和运行记录。成员可以创建根测试集、加入某个已保存修订、启动运行、记录每步 `PASS`/`FAIL`/`BLOCKED`、暂停/继续/完成并重新运行；查看者只读。运行会固定不可变清单，重跑会产生新的尝试，不会修改原测试用例修订。

后端单元/边界测试：

```powershell
Set-Location apps/server
mvn -B -ntp test
```

真实 PostgreSQL 集成测试使用 Testcontainers 的一次性数据库和受限 runtime（不读取日常 `.env`）：

```powershell
mvn -B -ntp -Pintegration verify '-Dbuild.commit=local-r06-m09-001'
```

本机无 Docker 时该命令会在 Testcontainers 启动阶段失败；这属于未运行的真实数据库证据，不能用单元测试替代。CI 的 `server` Job 在 Ubuntu 上执行同一集成 profile。前端回归仍使用：

```powershell
Set-Location apps/web
npm ci
npm run lint
npm run test:run -- --reporter=dot
npm run build
```

R06-M09-001-FIX01 在 V14 中以追加迁移补齐执行数据完整性：运行清单及步骤建立时受事务构建标记保护，保存后的 manifest/manifest step、终态 attempt 和运行步骤受数据库约束保护；步骤结果使用 `run_step.row_version`，暂停/继续/完成使用 `run_attempt.row_version`，接口同时要求一致的强 `If-Match` 和 JSON `expectedVersion`。事件、审计意图、Outbox 与业务写入同事务；幂等成功响应保存为冻结 JSON 快照，历史记录没有响应快照时返回 `IDEMPOTENCY_RESPONSE_UNAVAILABLE`，不会重新读取可变当前状态。

FIX01 的前端测试还覆盖待处理写操作、显示值与提交值一致、乱序运行响应和未知结果重试的同一幂等键。FIX02 增加真实数据库并发步骤/完成/重跑、同键冻结重放、sealed runtime 完整性、审计和 Outbox 故障回滚，以及真实 OIDC HTTP 的一条 M09 运行路径；FIX03 增加构建初始状态约束、可继续分页、运行汇总、页面重开和 VIEWER 直接写拒绝；`ManualExecutionDatabaseIT` 报告门禁要求至少包含历史记录中列出的确切用例，不能用总测试数替代。FIX04 将分页和汇总接入工作台：页面按 `nextCursor` 继续读取有限页，运行列表可用 `testSetId` 在数据库分页前过滤，汇总请求带同一范围并显示项目/测试集作用域；历史列表与选中尝试详情分开读取。V15 为正式的构建状态约束迁移；FIX04 新增正式 V16 终态事件保护，正常迁移后的专用迁移失败探针顺延使用故意失败的 V17，不修改正式迁移文件。报告门禁命令为：

```powershell
Set-Location apps/server
python ..\..\tools\verify_r06_manual_execution_report.py target\failsafe-reports
```

真实 OIDC HTTP 门禁还要求 `OidcCallbackSecurityIT.realOidcManualExecutionHttpRunsAndRejectsViewerWrites`；浏览器门禁要求新增的三用户 M09 页面流程。隔离 CI 中复跑浏览器时保持单 worker、零重试：

```powershell
Set-Location apps/web
npx playwright test e2e/manual-execution.spec.ts --workers=1 --retries=0
```

本机没有可用 Docker/Testcontainers 时，`mvn -B -ntp -Pintegration verify` 只能记录为 BLOCKED/NOT_RUN；单元测试、前端测试和规划工具通过不能替代真实 PostgreSQL 的 K01/K08/K09/K10 证据。`V15` 的构建标记由应用受控写入，V16 仅补充终态事件保护；直接数据库管理员可设置事务 GUC 的更高等级安全封装仍是后续运行时加固事项，不把它表述为生产级发布审批机制。

M09 尚未包含测试树、批量/参数/配置/环境、调度、Agent、附件、截图、缺陷关联、离线、导出或复杂报表。

## GitHub 发布

代码仓库已位于 [github.com/fangzhiy/Test365Alm](https://github.com/fangzhiy/Test365Alm)。本项目按任务分支和 Pull Request 协作；推送或 CI 的实际状态以 GitHub 页面和轮次记录为准，本地提交不代表 PR 已合并或生产已部署。不修改无关仓库，不强制推送。

```bash
gh auth login --hostname github.com
python tools/publish_github.py --owner fangzhiy --repo Test365Alm
python tools/publish_github.py --owner fangzhiy --repo Test365Alm --execute
```

## 实施纪律

按主方案前6周启动，再按迭代目标拆细Issue；使用真实样本测试迁移、版本和兼容；完成定义以证据为准。不要把全量目标缩水为几个管理页面，也不要把旧 ALM 服务端共存算成独立替代。
