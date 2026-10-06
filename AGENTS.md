# Test365Alm 工作规则

## 轮次协作

- 每轮开始先读取本文件、`README.md`、相关实施方案/模块/契约、`docs/development-status.md` 和任务清单。
- 先记录实际仓库、远端、分支、起始提交和工作区状态；不得把历史描述当作当前代码事实。
- 每轮只执行用户明确授权的范围；保留用户及其他 Agent 的已有修改。
- 每轮结束更新 `docs/development-status.md` 和 `docs/progress/runs/<round>-<task>.md`。
- 进度记录必须区分通过、失败、未运行、不适用和阻塞；不得把文档校验写成业务验收。
- 优先使用任务分支和 PR；没有实际 Git 仓库或远端时，明确记录，不能伪造提交、推送、PR 或审核结果。

## 项目状态

- 当前目录包含规划与研发辅助工具，以及 R02 工程底座、R03 身份/项目访问切片和 R04-M07-001 需求最小切片的 `apps/web` Vite 前端与 `apps/server` Spring Boot 服务；需求切片不代表完整 M07、M03 或产品完成。
- R03-M03-001 在独立 `feat/r03-m03-001` 分支增加本地 OIDC 首个切片；PR #1 未合并时，新 PR 依赖 `feat/r02-m02-001`。登录成功不代表已获得项目权限、RLS 或完整 M03。
- 所有产品验收、迁移、兼容、安全和性能结论必须有真实证据。
- `PLANNED`、`OPEN`、`BLOCKED`、`NOT_RUN` 不得被工具或文档改写成已完成。

## 现有命令

```bash
python tools/validate_package.py
python tools/calculate_budget.py
python -m unittest discover -s tools/tests -v
python tools/p0_health_check.py
cd apps/web; npm ci; npm run lint; npm run test:run; npm run build
cd apps/server; .\mvnw.cmd -B -ntp test
# 集成测试由 Testcontainers 创建本轮唯一的临时 PostgreSQL；不读取日常 .env，也不设置 TEST365ALM_IT_DATASOURCE_*。
cd apps/server; .\mvnw.cmd -B -ntp -Pintegration verify '-Dbuild.commit=local-r02'
# R05-M08-001：真实 PostgreSQL 手工测试用例/修订/权限报告门禁（FIX01 含 V11）
cd apps/server; .\mvnw.cmd -B -ntp -Pintegration verify '-Dbuild.commit=local-r05-m08-001'
python tools/verify_r05_test_case_report.py apps/server/target/failsafe-reports
# R03 FIX02：真实 HTTP OIDC 授权码/回调/PKCE/JWKS、运行时数据源与项目访问（10 个用例）
cd apps/server; .\mvnw.cmd -B -ntp -Pintegration verify '-Dit.test=OidcCallbackSecurityIT' '-Dbuild.commit=local-r03-fix02'
# CI/本地报告门槛：Failsafe 必须发现上述 7 个用例且 failures/errors/skipped 全为 0
python tools/verify_r03_oidc_http_report.py apps/server/target/failsafe-reports
# Linux/macOS: chmod +x ./mvnw && ./mvnw -B -ntp test
# PowerShell: if (-not (Test-Path .env.r02-test)) { Copy-Item .env.example .env.r02-test }
python tools/verify_r02_readiness.py --env-file .env.r02-test --compose-project test365alm-r02-manual --server-port 18081  # 构建 server jar 后，验证唯一临时 PostgreSQL 停止/恢复且后端不重启
python tools/verify_r02_migration_failure.py --env-file .env.r02-test --compose-project test365alm-r02-migration-manual --server-port 18082  # 临时失败迁移启动验证
python tools/verify_r03_project_access_report.py apps/server/target/failsafe-reports  # B 段 RLS/项目访问报告必须包含真实 PlatformDatabaseIT 场景
python tools/verify_r04_requirement_report.py apps/server/target/failsafe-reports  # R04 需求真实 PostgreSQL 并发/回滚用例及 V7→V9 升级/旧重放安全拒绝门槛
python tools/verify_r03_project_browser_report.py local-evidence/r03/playwright-results.xml  # CI-owned dual-user project grant/read/write-deny/revoke evidence
python tools/verify_r02_preexisting_project.py --env-file .env.r02-test  # 一次性对照项目及两条哨兵数据；验证两套脚本先拒绝碰触预存资源
python tools/prepare_r03_dev.py  # 仅首次生成被忽略的随机本地凭据和 Keycloak realm，不覆盖已有文件
docker compose --env-file .env.r03 -f compose.r03.yaml -p <本轮唯一项目名> up -d postgres keycloak
# PowerShell 后端：. ..\..\tools\import_r03_env.ps1 -Path ..\..\.env.r03; .\mvnw.cmd spring-boot:run
# PowerShell 前端：$env:TEST365ALM_DEV_BACKEND_URL='http://127.0.0.1:8080'; npm run dev
# 已启动隔离服务后，apps/web: npm run test:e2e
# R03 FIX03：共享 Keycloak 的项目 UI 对照和完整七用例均固定单 worker、零重试；CI 另存脱敏 project-ui-context-isolated/full.json
cd apps/web; npx playwright test e2e/project-access.spec.ts --grep "real Keycloak UI project flow" --workers=1 --retries=0
cd apps/web; npm run test:e2e  # playwright.config.ts 固定 workers=1、retries=0
# PowerShell: . .\tools\import_dev_env.ps1  # 从已有 .env 加载同一份开发配置，不覆盖它
```

Python 工具只使用标准库，支持 Python 3.10 及以上。新增工具必须保持离线可测试，不得默认调用 GitHub、客户环境或外部服务。

## 研发方向

当前工程方向保留 Spring Boot 4.1.1 + Java/Maven、React 19 + TypeScript + Vite 8、PostgreSQL 17。目标运行时为 Java 21、Node 24 LTS；本机实际版本和暂时偏差记录在 R02 轮次记录及 ADR 006，不得把目标环境写成已验证。计划中的对象存储、执行节点、兼容网关和业务模块仍按 P0/P1 顺序实现；不创建伪造的服务实现或客户适配器。

本机开发服务默认绑定 `127.0.0.1`；Compose 仅把 PostgreSQL 发布到回环地址。容器内绑定和宿主机端口暴露必须分开记录。

## 数据与接口约束

- 稳定实体身份与不可变修订分离。
- 历史运行固定 manifest；重试创建新 Attempt。
- 业务写入、审计意图和 Outbox 在同一事务。
- 权限覆盖读取、搜索、报表、导出、附件、后台任务和 AI。
- 创建命令支持持久化幂等键；更新使用版本条件。
- API 错误使用统一状态码和结构化错误体。

## 边界

- 始终：先读相关契约和模块手册；为新行为写可复现验证；保留未知项和差异证据。
- 需要评审：目标 ALM 版本/Edition、外部样本授权、数据库破坏性变更、兼容声明、依赖升级和预算变更。
- 禁止：提交秘密、使用未经授权的生产数据、把示例当黄金样本、修改原系统数据库、绕过商业许可、用规划包校验冒充业务验收。
- 健康接口只读；不得在探测、请求处理或测试脚本中执行 migrate、repair、clean 或自动修表。故障注入仅允许针对本轮隔离测试资源。
- Testcontainers 集成测试必须使用测试类动态注入的临时数据源；破坏性测试不得连接日常 `.env`、未知 JDBC 地址或用户数据库。对照容器必须证明其数据未被目标故障注入改变。
- 故障恢复/迁移失败脚本必须先确认端口、进程 PID、构建提交和 Compose 资源归属；无法验证监听或资源归属时记录 NOT_RUN/BLOCKED 并返回非成功状态，不得通过 observe/忽略错误判定通过。
- R02 故障脚本及 CI 清理共用 `tools/r02_resource_guard.py`；R03 浏览器作业通过 `tools/r03_resource_guard.py` 复用同一 manifest/归属校验：首次 up 前检查预存容器/网络/卷，记录本轮不可复用 run ID、Docker context/Engine 和资源 ID；stop、start、cleanup 前核对身份。只删除 manifest 中仍带本轮标签的资源，禁止项目级 `down --volumes --remove-orphans`。
- 故障脚本的子进程环境只能保留必要运行时变量和专用测试配置；Flyway 与 datasource URL 必须指向同一临时数据库，启动 JVM 时限定 Spring 配置加载位置。测试 `.env.r02-test` 不能覆盖日常 `.env`，不得继承父进程 Spring/JVM/Compose 偏转项。
- OIDC 身份只能由服务端验证的 issuer+subject 建立；不能信任请求头、前端 userId、邮箱或显示名合并。默认无 OIDC 配置时身份 API 拒绝访问。浏览器仅使用 HttpOnly 会话 Cookie；不得回传原始 OAuth token，退出必须有 CSRF，停用主体的下一次受保护请求必须失效。
- R03 的 Compose 与测试账户只用于本地/CI 隔离环境；`.env.r03` 和 realm import 含随机测试秘密，不提交。运行账号与 Flyway 迁移账号分离，运行账号不拥有表、DDL、BYPASSRLS。R03-M03-002 只实现项目访问/RLS 最小切片，不能据此标为完整 M03。正式 V8 迁移后，R02 故意失败测试必须先确认正式 V8 成功，再使用独立 V9 专属错误标记。
- R03 真实故障浏览器用例只在 CI 本轮唯一、带 `R03_RUN_ID` 资源标签的 Compose 项目中停启 Keycloak 或修改临时主体；普通 `npm run test:e2e` 不得触碰日常开发数据库或未知容器。CI 的 realm 导入文件仅容器 UID 1000 可读，`.env.r03` 与 realm 都是秘密；只上传脱敏的白名单证据和实际测试报告。
- R03-M03-002 FIX01/FIX02 的双用户项目浏览器用例只在当前 CI-owned Compose 项目中播种 tenant/domain/member 数据；普通本机浏览器运行会因资源归属门禁跳过并返回非成功报告，不能写成真实项目验收通过。项目授权迁移只新增 V5、V6，不改写已执行 V1-V4；V6 进一步把租户 bootstrap 限定为迁移/fixture 所有者边界。
- R03 FIX02 的 HTTP 回调测试必须保留真实应用过滤器链和同一 Cookie 容器；测试 IdP 只提供回环协议响应，不能用 `oidcLogin`、`@WithMockUser`、直接 SecurityContext 或 mock Principal/JWT 验证替代。每类非法 token 之后必须以独立 owner 连接比较 principal 全字段快照；只允许合成 subject 与 Testcontainers 临时 PostgreSQL，报告不得包含 Cookie、token、私钥或秘密。
- R04-M07-001 的需求接口仅覆盖项目根级、纯文本 title/body 和固定 priority；稳定需求身份与追加修订分离。写操作必须经过项目成员权限、同源 CSRF、幂等键和具体 `If-Match`；revision 只允许 runtime INSERT/SELECT，不能通过应用接口更新或删除历史。V7 是新增权威迁移，不改写 V1-V6；完整 M07、需求树、评审、附件和追踪继续保持未完成。
- R04-M07-001-FIX01 在 V8 中只追加约束和权限收口，不改写 V1-V7：current revision、outbox、幂等记录必须保持同租户/项目/需求归属；PROJECT_VIEWER 的 runtime SQL 写入、revision/outbox 修改和 TRUNCATE 必须被 RLS/GRANT 拒绝。幂等记录按租户/项目/主体/路由作用域检查有效期，新意图必须使用新键，当前格式重放返回冻结快照且重新授权；V7/V8 旧哈希经 V9 标记为不可安全重放，服务以 `IDEMPOTENCY_LEGACY_UNSUPPORTED` 拒绝而不拼装当前 ETag。审计和 Outbox 与业务写入同事务，失败必须回滚。FIX03 的集成门禁要求 `RequirementDatabaseIT` 并发/幂等/回滚用例以及独立 `RequirementMigrationUpgradeIT` 在真实 PostgreSQL 中先完成 V7→V9，再通过受限 runtime `RequirementService` 验证旧摘要安全拒绝、当前格式重放和撤权保护；不能以单元测试或直接 Controller 调用替代。
- R05-M08-001 只实现 MANUAL 测试用例第一条闭环：`test_case`、`test_revision`、`test_step` 使用 V10 受控迁移；FIX01 的 V11 在完整快照写入后封存 `test_revision`，受限 runtime 对已封存当前/历史修订的步骤 INSERT/UPDATE/DELETE/TRUNCATE 均不得成功，V10 既有行以 `created_at` 作为可追踪封存时间升级。项目成员可读写，viewer 只读，租户成员不因租户角色自动获得项目访问。显示编号由项目内分配器加行锁生成，步骤键由服务端生成且编辑调序保留；历史修订/步骤只读，创建/修订的业务、审计、Outbox 和幂等记录同事务。所有 runtime 查询须先通过测试用例专属项目权限边界；新建/编辑使用具体强 ETag 和 `Idempotency-Key`，未知字段、错误类型、跨范围引用和不支持类型必须拒绝。M08 Testcontainers 集成测试不得读取日常 `.env`，失败/回滚注入只能操作本轮临时数据库；完整 M08 的树、配置、参数、执行、附件和需求关联仍未实现。
- R06-M09-001 只实现手工执行最小闭环：根级 `test_set`、已保存 MANUAL 修订的固定 `test_instance`、事务内不可变 execution manifest、单次运行及 attempt、步骤结果、暂停/继续/完成、历史和受限重跑。V12 只追加基础迁移，V14 追加执行完整性、对象归属、终态/步骤状态约束和事件/幂等响应列，V15 进一步固定构建期间的初始状态，不改写 V1-V14；FIX04 的正式 V16 保护终态事件，之后专用故意失败探针使用 V17。manifest/manifest steps 运行后只读，部分唯一索引保证每个 run 只有一个未完成 attempt。运行写入使用真实受限 runtime、项目成员权限、RLS、正数版本条件和持久化幂等键；步骤使用自身 row_version，attempt 状态转换使用 attempt row_version；事件、审计意图和 Outbox 与业务写入同事务，成功幂等重放返回冻结响应，旧记录没有响应快照时拒绝。前端必须从真实 `/tests`/`revisions` 选择用例修订，不能以静态数据冒充已接通。M09 集成测试命令为 `mvn -B -ntp -Pintegration verify`，本机 Docker/Testcontainers 不可用时记录 BLOCKED，CI Ubuntu 的真实 PostgreSQL 结果才可作为 K01/K08/K09/K10 证据。测试树、批量、参数/配置/环境、调度、Agent、附件、截图、缺陷关联、离线、导出和复杂报表不属于本轮。
- R06-M09-001-FIX02 的真实数据库门禁要求 `ManualExecutionDatabaseIT` 的 17 个确切用例（正常闭环、并发版本/终态、同键冻结重放、sealed runtime 完整性、audit/Outbox 故障回滚和暂停重放）；总测试数不能替代名称门禁。`OidcCallbackSecurityIT.realOidcManualExecutionHttpRunsAndRejectsViewerWrites` 必须走真实 OIDC 会话、CSRF、Spring HTTP 和受限 runtime 数据源；浏览器 CI 还必须执行 `real Keycloak UI manual execution creates, resumes, finishes and reruns a two-step run`，共享 Keycloak 时固定 workers=1、retries=0。缺失报告、失败、错误或跳过均为失败，不能用历史 CI 数量冒充本轮证据。
- R06-M09-001-FIX04 的分页/汇总接口与工作台必须使用有限 keyset 页面而不是旧的全量数组接口：`/runs/page` 可带 `testSetId`，且过滤在数据库分页前执行；`/runs/summary` 可带同一 `testSetId`，无参数时才是完整项目范围。页面必须保存并继续使用不透明 `nextCursor`，把项目级与测试集级汇总清楚标示，历史列表和具体 attempt 详情分开读取；仅有后端 page/summary 方法而未有页面请求证据不能写成已接通。
