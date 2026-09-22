# Test365Alm 工作规则

## 轮次协作

- 每轮开始先读取本文件、`README.md`、相关实施方案/模块/契约、`docs/development-status.md` 和任务清单。
- 先记录实际仓库、远端、分支、起始提交和工作区状态；不得把历史描述当作当前代码事实。
- 每轮只执行用户明确授权的范围；保留用户及其他 Agent 的已有修改。
- 每轮结束更新 `docs/development-status.md` 和 `docs/progress/runs/<round>-<task>.md`。
- 进度记录必须区分通过、失败、未运行、不适用和阻塞；不得把文档校验写成业务验收。
- 优先使用任务分支和 PR；没有实际 Git 仓库或远端时，明确记录，不能伪造提交、推送、PR 或审核结果。

## 项目状态

- 当前目录包含规划与研发辅助工具，以及刚初始化的 `apps/web` Vite 前端和 `apps/server` Spring Boot 工程骨架；健康接口、数据库迁移和业务功能仍未实现。
- 所有产品验收、迁移、兼容、安全和性能结论必须有真实证据。
- `PLANNED`、`OPEN`、`BLOCKED`、`NOT_RUN` 不得被工具或文档改写成已完成。

## 现有命令

```bash
python tools/validate_package.py
python tools/calculate_budget.py
python -m unittest discover -s tools/tests -v
python tools/p0_health_check.py
cd apps/web; npm ci; npm run lint; npm run build
cd apps/server; .\mvnw.cmd test
```

Python 工具只使用标准库，支持 Python 3.10 及以上。新增工具必须保持离线可测试，不得默认调用 GitHub、客户环境或外部服务。

## 研发方向

当前工程基线候选为 Spring Boot 4.1.1 + Java 17 + Maven、React 19 + TypeScript + Vite 8、PostgreSQL 17。官方兼容性已核对，但最终锁定、构建和数据库验证必须以本轮实际结果为准。计划中的对象存储、执行节点、兼容网关和业务模块仍按 P0/P1 顺序实现；不创建伪造的服务实现或客户适配器。

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
