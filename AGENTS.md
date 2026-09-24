# Test365Alm 工作规则

## 轮次协作

- 每轮开始先读取本文件、`README.md`、相关实施方案/模块/契约、`docs/development-status.md` 和任务清单。
- 先记录实际仓库、远端、分支、起始提交和工作区状态；不得把历史描述当作当前代码事实。
- 每轮只执行用户明确授权的范围；保留用户及其他 Agent 的已有修改。
- 每轮结束更新 `docs/development-status.md` 和 `docs/progress/runs/<round>-<task>.md`。
- 进度记录必须区分通过、失败、未运行、不适用和阻塞；不得把文档校验写成业务验收。
- 优先使用任务分支和 PR；没有实际 Git 仓库或远端时，明确记录，不能伪造提交、推送、PR 或审核结果。

## 项目状态

- 当前目录包含规划与研发辅助工具，以及 R02 工程底座的 `apps/web` Vite 前端和 `apps/server` Spring Boot 服务；本轮只覆盖健康/版本接口、平台元数据迁移和状态工作台，不代表业务模块完成。
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
# Linux/macOS: chmod +x ./mvnw && ./mvnw -B -ntp test
# PowerShell: if (-not (Test-Path .env.r02-test)) { Copy-Item .env.example .env.r02-test }
python tools/verify_r02_readiness.py --env-file .env.r02-test --compose-project test365alm-r02-manual --server-port 18081  # 构建 server jar 后，验证唯一临时 PostgreSQL 停止/恢复且后端不重启
python tools/verify_r02_migration_failure.py --env-file .env.r02-test --compose-project test365alm-r02-migration-manual --server-port 18082  # 临时失败迁移启动验证
python tools/verify_r02_preexisting_project.py --env-file .env.r02-test  # 一次性对照项目及两条哨兵数据；验证两套脚本先拒绝碰触预存资源
python tools/prepare_r03_dev.py  # 仅首次生成被忽略的随机本地凭据和 Keycloak realm，不覆盖已有文件
docker compose --env-file .env.r03 -f compose.r03.yaml -p <本轮唯一项目名> up -d postgres keycloak
# PowerShell 后端：. ..\..\tools\import_r03_env.ps1 -Path ..\..\.env.r03; .\mvnw.cmd spring-boot:run
# PowerShell 前端：$env:TEST365ALM_DEV_BACKEND_URL='http://127.0.0.1:8080'; npm run dev
# 已启动隔离服务后，apps/web: npm run test:e2e
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
- R02 故障脚本及 CI 清理共用 `tools/r02_resource_guard.py`：首次 up 前检查预存容器/网络/卷，记录本轮不可复用 run ID、Docker context/Engine 和资源 ID；stop、start、cleanup 前核对身份。只删除 manifest 中仍带本轮标签的资源，禁止项目级 `down --volumes --remove-orphans`。
- 故障脚本的子进程环境只能保留必要运行时变量和专用测试配置；Flyway 与 datasource URL 必须指向同一临时数据库，启动 JVM 时限定 Spring 配置加载位置。测试 `.env.r02-test` 不能覆盖日常 `.env`，不得继承父进程 Spring/JVM/Compose 偏转项。
- OIDC 身份只能由服务端验证的 issuer+subject 建立；不能信任请求头、前端 userId、邮箱或显示名合并。默认无 OIDC 配置时身份 API 拒绝访问。浏览器仅使用 HttpOnly 会话 Cookie；不得回传原始 OAuth token，退出必须有 CSRF，停用主体的下一次受保护请求必须失效。
- R03 的 Compose 与测试账户只用于本地/CI 隔离环境；`.env.r03` 和 realm import 含随机测试秘密，不提交。运行账号与 Flyway 迁移账号分离，运行账号不拥有表、DDL、BYPASSRLS；后续项目授权/RLS 不能因本轮登录被标为完成。正式 V2 迁移后，R02 故意失败测试使用 V3 专属错误标记。
- R03 真实故障浏览器用例只在 CI 本轮唯一、带 `R03_RUN_ID` 资源标签的 Compose 项目中停启 Keycloak 或修改临时主体；普通 `npm run test:e2e` 不得触碰日常开发数据库或未知容器。CI 的 realm 导入文件仅容器 UID 1000 可读，`.env.r03` 与 realm 都是秘密；只上传脱敏的白名单证据和实际测试报告。
