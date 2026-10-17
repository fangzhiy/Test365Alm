# ADR 024 — 发布/周期、强类型冻结范围与同步质量快照

日期：2026-10-17；状态：采用于 R10 最小切片，待独立审核；不是完整 M06。

## 上下文与边界

PR #7/#8 已进入默认分支 `chore/r01-status-001@a2419e292c004b2cd4c7278bdaa40b1ce3442a22`，已有 M07 修订、M08 封存步骤、M09 固定 manifest、M10 typed 来源和 M11 typed trace。本轮复用这些事实，不引入第二套执行/追踪引擎，不生成综合质量分数，不实现排程、审批、附件或异步报表。

## 决定

1. 仅新增正式 V19，V1—V18 不改；故意失败探针顺延 V20。Release/Cycle 使用稳定 UUID、有限状态机、强 ETag 和日期包含约束。数据库父行锁及日期触发器防止父日期缩短使已有周期非法；关闭后只读，Release 仍可明确归档。
2. `release_scope` 使用强类型列和复合外键，不使用任意 JSON 引用。需求与用例都冻结实际当前 revision；用例必须已封存。范围按整个 Release 含周期去重，同一对象不能同时冻结不同修订；显式删除后重新加入才可更新。范围增删由数据库触发器推进 `scope_version`，不与内容 `row_version` 混用。
3. 每次 evaluate 在授权事务内锁定 Release，以单个 PostgreSQL SQL 语句取得跨模块一致的 MVCC 事实。按契约 v1 保存原始分子/分母、规则、范围版本/摘要和来源证据；读取历史只读保存值，不实时重算。相关测试存在活动 attempt 时，旧 PASS 不再算有效完成；最新完成必须匹配冻结 revision。有效追踪要求 ACTIVE 且两端 revision 仍当前。缺陷通过真实强类型链路匹配冻结范围，CLOSED 不算 open，REOPENED 算 open。
4. 有限同步边界：每个 Release 最多1000条活动范围成员关系；相关缺陷最多10000，超限 `409 SNAPSHOT_EVIDENCE_LIMIT`，不截断后声称完整。列表默认25、最大100、UUID keyset；摘要不包含大型证据，详情有上述界限。不承诺生产性能或完整 ALM 统计兼容。
5. ADMIN 才可写，MEMBER/VIEWER 可读，未入项目的 TENANT_ADMIN 不穿透。沿用真实 OIDC 会话、CSRF、动态成员授权及受限 runtime RLS。业务、范围版本、快照、audit、紧凑 typed Outbox、持久化幂等同事务；每次重放先重新鉴权。runtime 不具备 snapshot UPDATE/DELETE/TRUNCATE；直接 SQL 的跨范围写同样受限。
6. 页面复用已有项目/身份/权限轮次防护。读写分离，切范围取消并使旧回包失效；未知写入保留原键/内容/版本，确认成功后的读取失败只重试 GET；412 保留草稿，明确处理后才采用新版本。历史快照明确标示冻结结果。
7. 使用现有六 Job、单 worker、零重试；新增 M06 DB/非空 V18→V19/真实 OIDC HTTP 精确名称门禁，缺失、失败、错误、跳过、重复均不通过。旧 M10 升级测试明确停在 V18，新升级测试独立证明非空 V18→V19，不能仅调整旧测试数量冒充升级。

## 取舍与验证

拒绝 generic references、实时覆盖旧快照以及无界下载全部记录，避免无法核验归属与历史漂移。项目命令锁偏保守，但复用已验证事务边界，当前没有必要引入异步任务或大型状态管理框架。GUC 是受信任运行上下文，不宣称抵御 DBA 或 runtime 凭据完全失陷。

测试包含两个管理员并发版本冲突、同键并发、8项 audit/Outbox 故障回滚、范围冻结、动态失权、真实跨模块指标变化、直接 runtime SQL 和完整非空旧数据升级；具体已运行/未运行结果以 [R10 记录](../progress/runs/R10-CORE-BATCH03.md) 为准，不以本 ADR 代替验收。HTTP 和指标冻结口径见 [契约](../../contracts/releases.md)。
