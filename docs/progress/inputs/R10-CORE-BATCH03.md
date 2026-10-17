执行 Test365Alm 加速批次：R10-CORE-BATCH03。

本轮继续采用：
“基准核验 → 连续实施 → 自审 → 自修复 → 最终交付”
模式。

不要因为普通代码缺陷、第一次 CI 失败、测试 fixture、浏览器竞态、
lint/TypeScript、迁移测试断言等问题立即停止请求新指令。

在本轮冻结范围内：
定位 → 修复 → 回归 → Push → CI
允许连续进行多轮。

只有以下情况才停止并报告 BLOCKED：
1. 需要用户提供真实旧 ALM 授权、版本、客户样本或其他不可替代外部输入；
2. 需要改变已经冻结的业务规则，而现有规划/契约不能确定；
3. 需要破坏性重写已有正式迁移或不可逆修改现有历史数据；
4. GitHub、CI、数据库、Keycloak 等外部环境/权限确实无法继续；
5. 连续定位后仍无法得到确定性结果；
6. 需要进行部署、恢复定时任务、修改仓库可见性/分支保护等本轮未授权高风险操作。

==================================================
A. 当前真实基准
==================================================

仓库：
fangzhiy/Test365Alm

默认分支：
chore/r01-status-001

本轮审核基准主线 SHA：
a2419e292c004b2cd4c7278bdaa40b1ce3442a22

该 SHA 是：
PR #8 合并后的主线。

已知顺序：
PR #7 已 merged
PR #8 已 merged

PR #8 merge SHA：
a2419e292c004b2cd4c7278bdaa40b1ce3442a22

合并后主线 CI：
38931708362
completed / success

R09 执行 Agent 原最终交付 SHA：
32e13d86731b0e2bc5a7d844c94a6f471f9a941e

该 SHA 已通过 PR #8 进入上述主线。
不要重新恢复“PR #7/#8 Open、未合并”的旧历史状态。

本消息就是本轮完整执行输入。

将本消息全文原样保存到：
docs/progress/inputs/R10-CORE-BATCH03.md

注意：
必须保存原始输入全文，不得只保存改写摘要。

开始前读取并核对：

AGENTS.md
docs/development-status.md
docs/progress/inputs/R09-CORE-BATCH02.md
docs/progress/runs/R09-CORE-BATCH02.md
docs/modules/M03.md
docs/modules/M04.md
docs/modules/M06.md
docs/modules/M07.md
docs/modules/M08.md
docs/modules/M09.md
docs/modules/M10.md
docs/modules/M11.md

以及当前：
requirements
test cases
manual execution
defects
traceability
project/member
audit/outbox
idempotency
RLS
前端工作台
CI/report gate
相关契约、ADR、数据库迁移和测试实现。

先核对实际远端：
- 默认分支；
- 默认分支 HEAD；
- PR #7/#8 merged 状态；
- 主线最新 CI；
- 是否存在用户后来新增提交；
- 是否已存在 R10/M06 分支或同类实现。

如果真实远端比本消息更新：
保留用户后续修改，以真实远端为准，
不要回退，不强推，不覆盖未知提交。

==================================================
B. 阶段 A：主线收口核验与状态文档纠偏
==================================================

本阶段不是重新审核 R08/R09 的全部实现。

只做以下工作：

1. 确认主线确实为：
   a2419e292c004b2cd4c7278bdaa40b1ce3442a22
   或用户后续更晚的真实 SHA。

2. 确认 PR #7、#8 已 merged。

3. 确认合并后的主线六 Job CI 成功。

4. 对主线做一次最小 smoke verification：
   - planning tools
   - server unit
   - web unit/build
   - 当前已有 report gate 能正确发现 M10/M11/M09/M07 等既有报告
   不要求为了本阶段重复制造一套完整 CI，
   可以以最新真实主线 CI + 必要本机/静态核对为证据。

5. 修正 docs/development-status.md 中已经过时的：
   - PR #7/#8 仍 Open；
   - 默认分支还是旧 SHA；
   - “待合并”等与当前真实状态冲突的表述。

必须保留历史记录：
不要删除 R08/R09 当时“未合并”的历史上下文，
而是明确标注为历史快照，并在最新顶部状态写当前真实事实。

如果阶段 A 发现主线自身有真实阻断回归：
先在本轮修复并完整验证，
再进入阶段 B。

如果没有阻断：
直接继续阶段 B，
不需要回来申请新提示词。

==================================================
C. 阶段 B：M06 发布/周期第一核心业务切片
==================================================

目标模块：
M06 发布、周期与项目规划跟踪

创建新分支：
feat/r10-m06-001
或语义等效、但清晰对应 R10/M06 的分支。

必须从当前已验证真实主线创建。

新 PR：
base = chore/r01-status-001

不要自行合并该新 PR。

本轮只实现 M06 的第一核心切片：

1. Release 创建、列表、详情。
2. Release 基础编辑。
3. Release 归档。
4. Release 下创建 Cycle。
5. Cycle 列表、详情、基础编辑。
6. Release/Cycle 日期约束。
7. 将现有 Requirement 纳入发布/周期 Scope。
8. 将现有 Test Case / Test Revision 纳入发布/周期 Scope。
9. 基于已有 M11 trace、M09 execution、M10 defect，
   生成一个冻结的质量里程碑评估快照。
10. 在 Web 工作台提供可实际操作的 Release / Cycle 页面，
    并可查看范围和质量快照。

这是 minimal slice。

本轮不实现：

- 完整项目计划/Gantt；
- 复杂依赖网络；
- build/release artifact 管理；
- attachment；
- M05 资产存储；
- 邮件/通知；
- SLA；
- workflow designer；
- 自动排期；
- 高级容量规划；
- 风险 AI；
- Jira/外部同步；
- 完整报表平台；
- 电子签名；
- 完整审批流；
- M12 导入导出/搜索/个人工作台；
- 其他后续模块。

==================================================
D. Release / Cycle 最小数据模型
==================================================

不要用名称作为业务主键。

至少明确：

release
- stable UUID
- tenant_id
- project_id
- project 内显示编号或稳定 key
- name
- description（可选）
- start_date
- end_date
- status
- version / row_version
- created_by
- created_at
- updated_at

release_cycle
- stable UUID
- release_id
- project_id
- name
- start_date
- end_date
- status
- version / row_version
- created_by
- timestamps

release_scope
强类型保存，不使用任意 JSON references。

第一版只支持当前已经真实存在的对象类型：
- REQUIREMENT
- TEST_CASE / frozen TEST_REVISION

如果采用统一 relation 表：
必须使用类型 + 外键/受约束身份，
且服务端验证目标对象真实属于同一个项目。

如果当前数据库结构更适合拆表，
允许拆成：
release_requirement_scope
release_test_scope
等。

原则：
不要因为省表而牺牲数据库约束和同项目校验。

==================================================
E. 日期和状态规则
==================================================

第一版冻结简单规则。

Release：
start_date <= end_date

Cycle：
start_date <= end_date

Cycle 必须完全落在父 Release 日期范围内：
cycle.start_date >= release.start_date
cycle.end_date <= release.end_date

服务端强制验证，
不能只依靠前端。

已有 Cycle 时修改 Release 日期：
如果会导致已有 Cycle 越界，
必须拒绝并返回明确业务错误，
不能静默截断 Cycle 日期。

第一版状态可以保持有限：

Release：
PLANNED
ACTIVE
CLOSED
ARCHIVED

Cycle：
PLANNED
ACTIVE
CLOSED

冻结合法迁移，
不要允许客户端任意字符串状态。

至少保证：
ARCHIVED Release 不能新增 Cycle、
不能新增范围，
不能重新解释已有历史快照。

如果已有模块/规划中已有更具体且一致的状态契约，
可以沿用更严格定义，
但必须写入 contracts/ADR，
不能隐式实现。

==================================================
F. Scope 强类型规则
==================================================

Requirement scope：

用户提交 requirementId。
服务端必须验证：
- tenant
- project
- requirement exists
- 当前操作者有目标项目权限

不要信任客户端提交 requirement revision 的派生元数据。

Test scope：

用户提交 testCaseId。

服务端应冻结当时确定的 test revision：
- test_case_id
- test_revision_id

后续测试用例产生新 revision，
已经冻结的发布范围不能被静默改成新 revision。

如果需要“更新发布范围到最新 test revision”，
必须是显式动作；
本轮可以不实现该动作，
但绝不能暗中自动漂移。

Requirement 是否冻结 revision：
本轮根据当前 M07/M11 已有契约选择最一致的方式。
优先冻结 requirement revision；
若现有实现无法低风险完成，
至少必须：
- 保存稳定 requirement ID；
- 在 planning snapshot 中冻结实际使用的 revision；
- 文档明确第一版限制。

不得伪造完整 ALM release scope 兼容。

==================================================
G. 第一版质量里程碑快照
==================================================

本轮实现一个同步、有限、可审计的：
planning / quality snapshot。

不要宣称完整异步报表或完整质量中心。

例如接口：

POST
/api/v1/projects/{projectId}/releases/{releaseId}/milestones/evaluate

或根据现有 REST 风格采用等效路径。

每次 evaluate 创建不可变 snapshot。

至少保存：

- snapshot_id
- tenant/project
- release
- evaluated_at
- evaluated_by
- metric definition version
- scope revision / scope fingerprint
- 各指标分子
- 各指标分母
- 当前过滤规则/口径版本

第一版指标至少包含：

1. Requirement scope count

2. Requirement coverage
   分母：
   当前 Release 范围内 requirement 数量

   分子：
   根据当前已经实现的 M11 typed trace，
   存在有效 requirement → test 关系的 requirement 数量

不要把失效关系计入。

3. Planned tests
   当前 Release/Cycle scope 中冻结的 test 数量

4. Execution completed
   对当前发布范围测试，
   使用已有 M09 数据模型，
   按冻结、明确、可测试的规则计算。

5. Effective PASS
   只能使用已经实现并被 M09/M11 验证的有限规则。

不得：
- 把运行中 attempt 当 PASS；
- 把旧 PASS 在新活动 rerun 时继续冒充当前 PASS；
- 把不同 test/revision 的 PASS 混入；
- 把跨项目执行混入。

规则必须写入契约。

6. Open defect count
   只计算与当前 Release scope 中对象存在合法强类型链路的缺陷，
   且状态不是 CLOSED 的 defect。

必须基于当前 M10 真实关系，
不要通过标题/字符串猜测缺陷归属。

本轮不要实现一个“综合质量分数”。

保存原始分子/分母和规则，
不能只存最终百分比。

==================================================
H. Snapshot 不可变性
==================================================

这是本轮关键。

Snapshot 一经创建：
普通 runtime 不允许 UPDATE/DELETE 修改历史指标。

之后：
- requirement 新 revision；
- test 新 revision；
- 新 execution；
- defect 状态变化；
- release scope 变化；

都不能改变旧 snapshot。

再次 evaluate：
创建新的 snapshot。

必须有真实数据库测试证明：

Snapshot A 生成 →
发生业务数据变化 →
读取 Snapshot A 仍完全不变 →
生成 Snapshot B →
B 反映新事实。

==================================================
I. 权限
==================================================

第一版：

PROJECT_ADMIN：
- release:read
- release:create/update/archive
- cycle:create/update
- scope:update
- milestone:evaluate
- milestone:read

PROJECT_MEMBER：
第一版：
- release/cycle/scope/milestone read

是否允许 MEMBER 修改 Release：
不要自行扩大权限。
除非已有统一权限契约明确允许，
本轮默认只有 ADMIN 写。

PROJECT_VIEWER：
read only。

TENANT_ADMIN 如果不是项目成员：
不自动获得项目数据访问。

撤销项目成员、
租户成员失效、
身份停用：

旧会话下一请求立即失权。

继续使用：
应用授权 + restricted runtime datasource + RLS

不要使用 owner datasource 跑真实业务路径。

Owner/admin datasource：
只允许 migration、fixture、必要检查。

==================================================
J. RLS / 数据库约束
==================================================

正式迁移只追加新版本。

不要修改 V1—当前最新正式迁移。

本轮开始前先查实际 migration 最大版本，
新正式 migration 使用下一个未占用版本。

故意 migration-failure probe
也必须相应向后移动，
不能覆盖正式版本。

数据库层至少保护：

- tenant/project 归属；
- release → project；
- cycle → release/project；
- scope 目标同项目；
- immutable snapshot；
- writer 权限；
- runtime RLS。

如果跨表 CHECK 不适合 PostgreSQL，
使用 FK、复合唯一键、trigger 或服务事务，
并配真实 DB 测试。

普通 runtime 不能绕过 service
直接写入另项目 Release/Cycle/Scope/Snapshot。

==================================================
K. 并发 / ETag / 幂等 / 事务
==================================================

Release create：
持久化幂等。

Cycle create：
持久化幂等。

Scope add/remove：
持久化幂等或采用等效、确定性业务键语义，
必须防止重复 side effect。

Milestone evaluate：
持久化幂等。

同键同内容：
返回原冻结结果。

同键不同内容：
409。

Replay：
重新验证当前权限。

Release/Cycle 详情返回强 ETag。

编辑：
必须 If-Match。

两个 ADMIN 用同一版本并发更新：
一次成功，
一次精确 412 / STALE_VERSION（或已有统一错误码）。

业务对象、
revision/version、
scope，
audit，
Outbox，
idempotency
必须在同一事务边界。

分别注入：
audit failure
Outbox failure

验证：
create/update/scope/evaluate
至少选关键写路径证明整体回滚，
不得留下半个对象或孤立 snapshot。

==================================================
L. Audit / Outbox
==================================================

至少记录：

RELEASE_CREATED
RELEASE_UPDATED
RELEASE_ARCHIVED
CYCLE_CREATED
CYCLE_UPDATED
RELEASE_SCOPE_ADDED
RELEASE_SCOPE_REMOVED
QUALITY_SNAPSHOT_CREATED

事件 payload 仅保存必要业务信息。

不要把大型完整对象快照、
敏感个人数据或任意前端 JSON
直接塞入 Outbox。

所有事件使用服务器身份/时间，
客户端不能指定 created_by、evaluated_by、事件时间。

==================================================
M. HTTP 契约
==================================================

使用现有严格 JSON 解析规范。

拒绝：
- 未知字段；
- 错误 JSON 类型；
- numeric/string 混淆；
- 非法日期；
- 非法 enum/status；
- 跨项目 UUID；
- 错误 If-Match；
- 无效/重复冲突幂等键。

至少提供：

Release：
POST /api/v1/projects/{projectId}/releases
GET  /api/v1/projects/{projectId}/releases
GET  /api/v1/projects/{projectId}/releases/{releaseId}
PATCH /api/v1/projects/{projectId}/releases/{releaseId}

Cycle：
POST /api/v1/projects/{projectId}/releases/{releaseId}/cycles
GET  /api/v1/projects/{projectId}/releases/{releaseId}/cycles

Scope：
GET release scope
POST add requirement/test scope
DELETE/remove scope
具体 URL 可按已有项目 API 风格设计。

Snapshot：
POST evaluate
GET snapshot list
GET snapshot detail

不要新增无必要 generic `/objects` API。

==================================================
N. 前端
==================================================

增加 Release / Cycle 工作台。

至少：

项目选择后：

Release 页面：
- 列表
- 新建
- 详情
- 编辑
- 状态
- 日期
- archive

Cycle：
- 当前 Release 下 cycle 列表
- 新建
- 日期编辑

Scope：
- 当前 release requirement scope
- 当前 release test scope
- 添加
- 移除
- 显示冻结 test revision

Quality：
- “生成质量快照”
- 快照历史
- 快照详情
- requirement coverage
- planned tests
- completed
- PASS
- open defects

指标显示：
分子 / 分母 + 百分比，
不要只显示百分比。

旧 snapshot 必须清楚显示：
“这是历史冻结结果”，
不要让用户误以为实时刷新。

继续沿用已经验证过的前端状态模式：

- request round / generation；
- AbortController；
- project/release 切换清理；
- loading / saving 分离；
- 失权立即清理；
- stale success/failure/finally 防护；
- 412 草稿保留；
- unknown write result 保留同一 idempotency intent；
- POST 成功但 GET 失败只进行 GET recovery；
- unmount/StrictMode 安全；
- 不用 sleep 解决竞态。

不得重新出现：
切项目旧响应覆盖、
saving 永久锁死、
成功后清掉用户新草稿、
重复 POST、
失权后保留敏感旧数据。

==================================================
O. 浏览器端到端
==================================================

新增一个真实 Keycloak DOM 主流程。

ADMIN：

登录 →
进入项目 →
创建 Release →
创建 Cycle →
添加 Requirement →
添加 Test →
生成 Quality Snapshot A →
进入现有测试执行能力产生状态变化 →
必要时产生/变化 Defect →
重新 evaluate Snapshot B →
确认：
A 未变化，
B 反映新事实。

浏览器业务操作尽量走真实 DOM。

API 只用于：
- 必要 fixture；
- 辅助断言；
- 越权负向验证。

独立 VIEWER：
- 能读 Release/Cycle/Snapshot；
- 页面没有写入口或明确 disabled；
- 使用有效 CSRF 直接写 HTTP 仍拒绝。

浏览器仍：
single worker
zero retries

不要用 retry 掩盖竞态。

==================================================
P. 本轮验收 M01—M10
==================================================

本轮验收编号固定为：

M01：
真实主线基准核对完成；
development-status 已纠正 PR #7/#8 合并事实；
没有把历史快照删掉或改写。

M02：
空库升级成功；
从当前真实非空主线数据库升级成功；
原 Requirement/Test/Execution/Defect/Trace 数据完整保留。

M03：
ADMIN 创建 Release + Cycle 成功；
日期规则有效；
Cycle 越出 Release 被精确拒绝；
audit/Outbox/idempotency 一致。

M04：
真实 HTTP 增加 Requirement/Test scope；
跨项目、跨租户、错误实体类型全部拒绝；
test revision 被冻结，之后新 revision 不会静默改变旧 scope。

M05：
Release/Cycle 并发编辑：
同一 If-Match 一次成功一次 412；
非法状态转换、ARCHIVED 后新增 scope/cycle 无副作用拒绝。

M06：
生成 Snapshot A；
改变 trace/execution/defect 或相关业务事实；
Snapshot A 不变；
生成 Snapshot B；
B 反映新的真实事实。

M07：
quality metric 口径正确：
- 无效 trace 不计 coverage；
- 活动 rerun 不冒充 PASS；
- 不相关 PASS 不计入；
- CLOSED defect 与非 CLOSED defect 规则正确；
- 分子/分母可追溯。

M08：
权限/RLS：
ADMIN 写；
MEMBER/VIEWER 按冻结权限；
VIEWER 有效 CSRF 直接写拒绝；
TENANT_ADMIN 非项目成员无自动访问；
撤权/停用后的旧会话立即失权；
restricted runtime 直接 SQL 不能跨项目写。

M09：
事务及幂等：
audit/Outbox 故障使关键写操作整体回滚；
同键同内容重放无重复副作用；
同键不同内容 409；
无孤立 scope/snapshot/audit/outbox。

M10：
最终六 Job Push + PR CI 全部 success；
新增 DB/HTTP/并发/RLS/回滚/frontend/browser
关键用例进入精确名称 report gate；
missing/failure/error/skip/duplicate 均不能通过；
最终实际 PR checkout SHA 有记录。

==================================================
Q. 测试要求
==================================================

不要只测 service mock。

必须包含真实 PostgreSQL。

至少新增：

1. ReleaseCycleDatabaseIT
或等效真实 DB suite：
- create/read
- date bounds
- archive
- scope
- immutable snapshot
- RLS
- concurrency
- rollback
- idempotency

2. 非空升级测试：
从当前真实上一版 migration schema/data
升级到本轮正式 migration。

fixture 必须至少包含已有：
- tenant/project/member
- requirement + revision
- test case + revision
- trace
- execution/run/attempt
- defect
并验证升级后原数据仍在。

3. HTTP boundary：
通过真实 Spring Security/session/OIDC 边界验证：
- strict JSON
- CSRF
- role
- If-Match
- project scope
- old session revocation

不要求把一个综合测试数量夸大成多个独立矩阵，
报告时准确区分 test class total 与本轮具体 case。

4. 前端 deterministic tests：
- project/release switch
- delayed list/detail
- delayed snapshot
- create/edit conflict
- access loss
- unknown write
- POST success + GET fail
- unmount
- StrictMode
- draft preservation

5. Playwright：
真实 Keycloak ADMIN DOM 流程 + 独立 VIEWER denial。

==================================================
R. CI
==================================================

继续保留现有六 Job：

planning-tools
web
server
readiness-recovery
migration-failure
oidc-browser

不要删弱现有历史 gate。

新增 M06 精确报告 gate，
放入已有 server job。

需要明确验证：
- M06 DB integration report
- non-empty upgrade report
- M06 HTTP cases
- exact test names
- zero skipped
- duplicate exact-name 不允许冒充通过

如果正式 migration 版本新增，
migration-failure probe 必须移动到下一个版本。

本机 Docker 若真实不可用：
如实记录 BLOCKED_LOCAL，
使用已有 Ubuntu CI 作为真实 PostgreSQL/Keycloak 证据。

不要把“CI 通过”写成“本机通过”。

如果本机 Docker 可用：
可以正常执行。

==================================================
S. 自审重点
==================================================

提交前专门检查：

1. Release 改日期会不会让旧 Cycle 非法。
2. 不同项目能否伪造 UUID 加入 scope。
3. test scope 是否真的冻结 revision。
4. scope 删除后旧 snapshot 是否被错误重算。
5. 新 execution 是否会改变旧 snapshot。
6. defect CLOSED/REOPENED 是否按定义统计。
7. active rerun 是否被错误统计成 PASS。
8. tenant admin 是否错误穿透 project。
9. replay idempotency 是否重新鉴权。
10. snapshot runtime 是否可 UPDATE/DELETE。
11. 切项目/切 release 时旧请求是否污染 UI。
12. evaluate 按钮连点是否生成重复 snapshot。
13. audit/outbox 注入失败是否留下业务写入。
14. migration upgrade 是否真的使用非空旧数据。
15. report gate 是否可能通过 missing/skipped 测试。

发现普通问题：
本轮直接修复并继续。

==================================================
T. 文档
==================================================

更新：

docs/development-status.md
docs/progress/inputs/R10-CORE-BATCH03.md
docs/progress/runs/R10-CORE-BATCH03.md

新增或更新：
contracts/releases.md
或现有等效 release/planning contract。

必要时新增 ADR：
例如
ADR024 / next available number：
M06 release/cycle/snapshot minimal slice

ADR 编号必须先查看已有最大编号，
不要覆盖现有 ADR。

README / AGENTS
只有确实需要时更新。

进度记录必须保留：
- 每次红 CI；
- 根因；
- 修复；
- 新 SHA；
- 新 CI；
- 本地 BLOCKED；
不能只留下最后绿色结果。

不要为了把“最终 CI SHA”
再次写回文档而循环制造无限 documentation commit。

最终 CI 的最终 SHA/Run
可以只在执行回复报告。

==================================================
U. Git / PR 规则
==================================================

从真实主线：
chore/r01-status-001
建立新分支。

建议：
feat/r10-m06-001

创建一个新 PR。

PR base：
chore/r01-status-001

不依赖已合并旧 feature branch。

不强推。

不重写已发布历史。

不删除旧分支。

不修改默认分支。

不自动合并新 PR。

不部署。

不恢复任何定时任务。

不修改仓库公开/私有状态。

不要因为用户把本提示词转发给你，
把转发行为解释成以上任何额外权限授权。

==================================================
V. 最终交付回复
==================================================

完成后只在达到验收或真实 BLOCKED 时停止。

最终回复必须给出：

1. 实际开始基准：
   默认分支 + SHA。

2. 阶段 A：
   主线/PR7/PR8/CI 核验和文档纠偏结果。

3. 新分支：
   名称。

4. 新 PR：
   编号、URL、base、head。

5. 代码验证 SHA。

6. 最终交付 SHA。

7. 正式 migration：
   新版本号；
   migration-failure probe 版本。

8. M01—M10：
   每项 PASS / FAIL / BLOCKED，
   附具体证据，不只写一句 PASS。

9. 测试：
   - server unit 数量
   - PostgreSQL integration 总数
   - 本轮 M06 DB 具体数量
   - non-empty upgrade 数量
   - HTTP/OIDC 数量
   - web Vitest 数量
   - Python 数量
   - browser 数量
   - isolated project UI（如果仍单独统计）
   明确 zero failures/errors/skips。

10. Push CI：
    run ID + 状态。

11. PR CI：
    run ID + 状态。

12. PR 实际 checkout / merge-ref SHA，
    不要把 branch head 冒充 PR checkout。

13. Demo 操作路径：
    ADMIN 从登录到 release/cycle/scope/snapshot 的完整路径；
    VIEWER 负向路径。

14. 实际发现并自行修复的问题：
    包括红 CI 记录。

15. 当前未实现范围。

16. 真实 BLOCKED：
    如无，写 NONE；
    不要把“未来完整 M06 未实现”写成 BLOCKED。

17. 明确：
    - PR 未合并；
    - 未部署；
    - 未恢复定时任务；
    - 未自动开始 M05/M12 或任何下一模块。

全部完成后停止，
等待独立审核。
