# ADR 023 — 缺陷核心、冻结失败链路与当前解决周期复测

日期：2026-10-16；状态：采用于 R09 最小切片，须独立审核，不代表完整 M10/M05。

## 上下文与边界

R08 已提供 typed requirement→test revision 追踪，M09 已有固定 manifest、不可变终态 attempt、独立 rerun 与事务证据。M10 复用这些能力，不创建第二套执行引擎，不编造自定义工作流、业务扩展值、异步投影或通知能力。PR #7 未合并，因此 M10 PR #8 从已通过阶段 A 六 Job 的 `aaf18e5726037afe5c6cf693ca1eb7449faa8141` 建立，base `feat/r08-m04-m11-001`；本轮不合并。

## 决定

1. 正式新增 V18；V1—V17 不改。稳定 defect UUID、项目 BUG 编号、revision UUID 分离。分配器行锁及项目命令锁保证并发编号/幂等；修订追加，current 指针与完整缓存字段使用 deferred 复合 FK 验证。历史不给 runtime UPDATE/DELETE/TRUNCATE。
2. `defect_link` 使用 REQUIREMENT/RUN_STEP 强类型行和复合 FK。失败来源只接受已完成 FAIL/BLOCKED；服务端从原 run/attempt/manifest/step 读取 test/revision、实际/预期、结论及版本。自动绑定相同测试修订的当前有效需求关系（最多100，超出明确拒绝）。创建事务快照触发器拒绝后续追加或不匹配内容；不以 JSON references 代替归属。
3. 固定有限状态机；RESOLVED 记录非空修复说明，普通非执行来源缺陷可从 RESOLVED 关闭。来源缺陷必须使用缺陷 retest 命令明确绑定既有 M09 新 attempt 与本次 resolved revision；只有本周期最新相关 FINISHED PASS、绑定步骤 PASS 且无后续尝试才满足关闭。无关 PASS、FAIL、活动尝试、前一解决周期均无效。REOPENED 保留旧周期记录但 verification=false；CLOSED 可查看原历史证据。关闭时服务和数据库重新验证，不相信前端布尔值。
4. 使用现有真实 OIDC、动态项目权限与 runtime RLS。ADMIN/MEMBER 读写、VIEWER 只读；TENANT_ADMIN 不隐式获得项目权。复测还经独立 M09 写权限验证。每个写操作强版本、持久化幂等、业务/审计/专属 typed Outbox/冻结响应同事务；错误继续既有结构和状态码，不吞异常。
5. 前端按主体/项目/权限/reset 重挂载隔离；操作轮次、Abort 与8秒有界计时器兼用。未知写意图锁定原 key/内容/版本，确认成功后读取失败只重试 GET。412 保留基础版本，明确采用最新或放弃才能继续。来源创建失权传播到整个父范围；多个失败步骤按来源ID计数写入锁，任一完成不释放其他未知操作。列表和创建互斥，旧列表不能移除刚确认的新缺陷。草稿与HTTP序列化均只取四个可写字段，不回传作者/时间/状态等只读修订元数据。
6. 测试只使用自有 Testcontainers/CI-owned Compose，owner 仅迁移/夹具/独立核验，真实业务经受限 runtime。正式 V18 后故意失败探针为独立 V19，保留正确归属清理、回环及配置隔离。新增精确方法门禁和缺失/失败/错误/跳过/重复的离线负向测试；旧六 Job、单 worker、零重试不变。

## 结果、验证与限制

新 `DefectDatabaseIT` 覆盖同键并发、并发412、合法/非法状态、来源/周期、writer SQL边界及六项 audit/Outbox 回滚；`DefectMigrationUpgradeIT` 先运行真实 V17 runtime 业务形成非空需求/用例/运行/追踪/旧幂等，再升级 V18、全表原值对比及再次迁移0，升级后原业务可读/重放并可创建来源缺陷。两项新增真实 OIDC HTTP 测试与一个真实 Keycloak MEMBER DOM 闭环不冒充整个 OIDC/浏览器套件数量。实际 SHA/报告/失败修复见 [轮次记录](../progress/runs/R09-CORE-BATCH02.md)。

直接运行账号可设置项目上下文 GUC；仍依有效数据库成员关系/RLS，非抵御 DBA 或 runtime 凭据完全失陷的安全平台。项目锁偏保守，会串行化同项目命令；缺陷列表默认25、最多100，详情来源最多101，有限 N+1 未承诺生产性能指标。无生产升级/性能/全量兼容声明。编译 target17 不变，本机 Java17/Node26 与 CI Java21/Node24 的差异如实记录，不升级整套依赖。

设计依据：[PostgreSQL 17 约束](https://www.postgresql.org/docs/17/ddl-constraints.html)、[行级安全](https://www.postgresql.org/docs/17/ddl-rowsecurity.html)、[显式锁](https://www.postgresql.org/docs/17/explicit-locking.html)。这些文档支持机制选择，不代替本项目实际验收。
