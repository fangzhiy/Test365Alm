# Test365Alm 开发状态

最后更新：2026-09-24
当前轮次：`R03-M03-001-FIX02`
状态：`AUTH_SLICE_IMPLEMENTED / FIX02_HTTP_EVIDENCE_GREEN`

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
