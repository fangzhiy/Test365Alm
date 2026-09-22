# Test365Alm

企业级应用质量管理平台研发规划基线。对标原 ALM/Quality Center 产品体系；具体目标版本、Edition、扩展和旧生态兼容范围须在 P0 冻结。

> **当前状态：文档与研发辅助工具已建立；业务应用尚未开发。** 所有产品任务为 PLANNED，验收为 NOT_RUN。本包没有宣称已完成 ALM 克隆、已经上线或已经通过兼容认证。

当前开发状态以 [development-status.md](docs/development-status.md) 和 [轮次记录](docs/progress/runs/R01-STATUS-001.md) 为准；`apps/web` 与 `apps/server` 目前仅是工程启动骨架。

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

当前会话连接器没有新建仓库动作，未在远端创建或上传。本地准备好 Git 与 GitHub CLI，登录授权后按发布手册执行。发布器默认为预览，只有明确加 `--execute` 才创建私有仓库；不修改无关仓库，不强制推送。

```bash
gh auth login --hostname github.com
python tools/publish_github.py --owner fangzhiy --repo Test365Alm
python tools/publish_github.py --owner fangzhiy --repo Test365Alm --execute
```

## 实施纪律

按主方案前6周启动，再按迭代目标拆细Issue；使用真实样本测试迁移、版本和兼容；完成定义以证据为准。不要把全量目标缩水为几个管理页面，也不要把旧 ALM 服务端共存算成独立替代。
