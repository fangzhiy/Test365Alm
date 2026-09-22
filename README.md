# Test365Alm

企业级应用质量管理平台研发规划基线。对标原 ALM/Quality Center 产品体系；具体目标版本、Edition、扩展和旧生态兼容范围须在 P0 冻结。

> **当前状态：R02 工程底座最小切片正在实施。** 健康/版本接口、受控迁移、前端状态工作台和自动化测试以实际代码与轮次记录为准；这不代表完整 ALM 产品、兼容认证或生产部署已经完成。

当前开发状态以 [development-status.md](docs/development-status.md) 和 [R02-M02-002 轮次记录](docs/progress/runs/R02-M02-002.md) 为准；上一轮记录仍保留供追溯。

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

要复现数据库故障场景，保持后端进程运行，执行 `docker compose --env-file .env -p test365alm-r02 stop postgres`，确认 live 仍为 200 且 ready 为 503；随后执行 `docker compose --env-file .env -p test365alm-r02 start postgres`，ready 应在下一次探测恢复为 200。该场景只针对本轮隔离 Compose 项目，不要对用户已有数据库使用清库或删除卷。

也可以在构建后端 jar 后运行可重复的自动检查（脚本只停止并恢复 `test365alm-r02` 项目的 PostgreSQL，不删除数据卷）：

```powershell
python tools/verify_r02_readiness.py
```

### 可复现验证命令

```bash
python -m unittest discover -s tools/tests -v
python tools/validate_package.py
python tools/p0_health_check.py
cd apps/web && npm ci && npm run lint && npm run test:run && npm run build
cd ../server && ./mvnw -B -ntp test
TEST365ALM_IT_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:54329/test365alm \
TEST365ALM_IT_DATASOURCE_USERNAME=test365alm \
TEST365ALM_IT_DATASOURCE_PASSWORD=test365alm_dev_password \
./mvnw -B -ntp -Pintegration verify -Dbuild.commit=local-r02
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

## GitHub 发布

代码仓库已位于 [github.com/fangzhiy/Test365Alm](https://github.com/fangzhiy/Test365Alm)。本项目按任务分支和 Pull Request 协作；推送或 CI 的实际状态以 GitHub 页面和轮次记录为准，本地提交不代表 PR 已合并或生产已部署。不修改无关仓库，不强制推送。

```bash
gh auth login --hostname github.com
python tools/publish_github.py --owner fangzhiy --repo Test365Alm
python tools/publish_github.py --owner fangzhiy --repo Test365Alm --execute
```

## 实施纪律

按主方案前6周启动，再按迭代目标拆细Issue；使用真实样本测试迁移、版本和兼容；完成定义以证据为准。不要把全量目标缩水为几个管理页面，也不要把旧 ALM 服务端共存算成独立替代。
