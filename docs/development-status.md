# Test365Alm 开发状态

最后更新：2026-09-22  
当前轮次：`R01-STATUS-001`  
状态：`INVENTORY_RECORDED / IMPLEMENTATION_NOT_STARTED`

## 实际仓库状态

- 工作目录：`D:\code\project\test365Alm\jihua\Test365Alm_ProjectPackage\Test365Alm`
- Git 仓库：已在本轮初始化；项目根为当前工作目录
- 当前分支：`chore/r01-status-001`
- 起始状态：本轮初始化前无提交；基线提交为 `ec350ce4b991f4bd427fd630332d7e6f7069262e`
- 远端地址：`https://github.com/fangzhiy/Test365Alm.git`
- 远端分支：`origin/chore/r01-status-001` 已推送并核验，当前最新提交为 `8c998aac46cbc5a0693640390eb43198359f3f43`
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
