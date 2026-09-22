# Test365Alm 开发状态

最后更新：2026-09-22
当前轮次：`R02-M02-001`
状态：`IMPLEMENTED_LOCALLY / FINAL_ACCEPTANCE_PENDING`

本页保留 R01 的盘点事实，并在下方记录 R02 的实际工程进展。R02 只实现 M02 工程底座最小切片，不代表整个 M02、P0 或完整 ALM 产品已完成。

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
