# Test365Alm

企业级应用质量管理平台研发规划基线。对标原 ALM/Quality Center 产品体系；具体目标版本、Edition、扩展和旧生态兼容范围须在 P0 冻结。

> **当前状态：R05-M08-001 手工测试用例第一切片实施中。** R02 工程底座、R03 OIDC/项目访问和 R04 需求切片保留；新增用例闭环不代表完整 M08、M07、M03 或完整 ALM 已完成。

当前开发状态以 [development-status.md](docs/development-status.md) 和最新轮次记录为准；R03/R02 历史记录保留供追溯。

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

启动迁移失败验证保留正式 V9，向临时目录注入独立的 V10 故障迁移，不修改正式 `db/migration`；脚本先确认正式 V9 已成功记录：

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

FIX01 的前端测试还覆盖待处理写操作、显示值与提交值一致、乱序运行响应和未知结果重试的同一幂等键。V14 正常迁移后的专用迁移失败探针使用故意失败的 V15，不修改正式迁移文件。报告门禁命令为：

```powershell
Set-Location apps/server
python ..\..\tools\verify_r06_manual_execution_report.py target\failsafe-reports
```

本机没有可用 Docker/Testcontainers 时，`mvn -B -ntp -Pintegration verify` 只能记录为 BLOCKED/NOT_RUN；单元测试、前端测试和规划工具通过不能替代真实 PostgreSQL 的 K01/K08/K09/K10 证据。`V14` 的构建标记由应用受控写入；直接数据库管理员可设置事务 GUC 的更高等级安全封装仍是后续运行时加固事项，不把它表述为生产级发布审批机制。

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
