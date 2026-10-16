执行 Test365Alm 加速批次：R09-CORE-BATCH02。

本轮采用“连续实施 + 自审 + 自修复 + 最终交付”模式。
不要每发现一个普通测试问题就停止回来要新指令。

只有以下情况才停止并报告 BLOCKED：
1. 需要用户提供真实旧 ALM 授权、版本或兼容样本；
2. 需要改变已经冻结的产品业务规则；
3. 需要破坏性修改已有生产型迁移或历史数据；
4. 外部环境/权限确实无法继续；
5. 连续定位后仍无法得到确定性结果。

普通代码缺陷、测试失败、CI失败、竞态、契约不一致、
RLS、迁移测试、页面状态问题，由你在本轮自行分析、修复、
重新验证，直到本轮验收条件通过或明确 BLOCKED。

==================================================
A. 当前基准
==================================================

仓库：
fangzhiy/Test365Alm

当前开放：
PR #7
分支：feat/r08-m04-m11-001
base：chore/r01-status-001

审核交付 SHA：
740ac21980b41a8ca49be616e7df4a4b8904e881

当前 PR CI：
38213441555
completed / success

PR #1—#6 已经进入当前主线。
不要根据旧进度文档里“未合并”的历史快照恢复旧状态。

本消息全文保存到：
docs/progress/inputs/R09-CORE-BATCH02.md

先读取：
AGENTS.md
docs/development-status.md
R08 输入与执行记录
M04、M10、M11 模块计划
domain model
已有需求、用例、执行、追踪契约及数据库实现。

核对真实远端、PR、默认分支和后续提交。
保留用户改动，不回退、不强推。

不自动合并 PR，不部署生产，不恢复定时任务，
不修改默认分支保护或仓库可见性。

==================================================
B. 阶段 A：快速收口 PR #7
==================================================

不要重新实现 M04/M11。

对 PR #7 做一次完整但限定范围的独立自审：

M04：
- SYSTEM 与 CUSTOM 字段身份是否严格隔离；
- TEXT/NUMBER/ENUM 输入、枚举值、长度、类型及非法值；
- 不可变字段定义修订；
- 同租户不同项目和跨租户隔离；
- ADMIN 写、MEMBER/VIEWER 读边界；
- runtime RLS 及直接 SQL 写边界；
- 并发修改、ETag/版本控制；
- audit/Outbox/幂等事务回滚；
- 页面项目切换、失权、迟到响应和 loading。

M11：
- requirement → test case typed relation 的双端归属；
- 冻结 requirement revision / test revision；
- 同项目不同实体、跨项目、跨租户错误引用；
- 失效历史不被静默删除；
- 去重和并发创建；
- 幂等冻结结果和重放重新授权；
- VIEWER 只读；
- MEMBER 合法关联；
- 撤权/停用后旧会话直接失权；
- audit/Outbox 故障整体回滚；
- 分页无漏项、重复；
- 当前同步汇总只宣称已经实现的有限规则，
  不冒充完整异步 traceability projection。

重点重新验证：
需求关联一个测试 → 未运行时覆盖 0/1 →
实际执行 PASS 后变成 1/1。
同时验证 FAIL、活动重跑和失效关系不会被错误算成 PASS。

发现问题：
直接在 PR #7 当前分支修复。
同一个问题最多进行必要的连续修复，不创建大量 FIXXX 轮次。
按逻辑分组提交即可。

阶段 A 完成门：
- 必要新增测试全部通过；
- 当前六 Job Push + PR CI 通过；
- M04/M11 最小切片没有已知阻断缺陷；
- 文档准确说明只是 minimal slice。

如果阶段 A 需要用户决策或存在真实 BLOCKED，
停止，不进入阶段 B。

阶段 A 没有阻断：
继续阶段 B，不需要回来请求下一段提示词。

==================================================
C. 阶段 B：M10 缺陷管理第一核心闭环
==================================================

创建：
feat/r09-m10-001
或等效分支。

如果 PR #7 尚未合并：
从阶段 A 验证后的 PR #7 head 建分支，
新 PR base 使用 feat/r08-m04-m11-001，
明确依赖 PR #7。

如果 PR #7 已被用户合并：
从真实主线建立分支。

不要自行合并 PR #7。

本轮只做 M10 第一业务切片：

1. 创建缺陷。
2. 缺陷列表、详情。
3. 编辑缺陷。
4. 状态流转。
5. 不可变变更历史。
6. 从失败的 run / attempt / step 创建缺陷。
7. 缺陷关联 test case、test revision、run、
   attempt、step。
8. 能关联 requirement 时建立强类型关系。
9. 修复后从缺陷进入一次复测。
10. 复测 PASS 后允许按规则关闭缺陷，
    同时保留原失败和原缺陷历史。

不实现：
附件/截图、
邮件通知、
完整自定义工作流设计器、
SLA、
复杂缺陷树、
批量操作、
外部 Jira 同步、
AI 分类、
完整报表。

==================================================
D. 缺陷数据模型
==================================================

至少区分：

defect
- 稳定 UUID
- 项目内显示编号，例如 BUG-1
- tenant/project
- 当前状态
- 当前严重程度/优先级
- 标题
- 当前版本
- 创建者/时间
- 当前修订指针

defect_revision
- 不可变修订
- 标题
- 描述
- severity
- priority
- status
- author
- timestamp

defect_link
强类型关系，不使用一个任意 JSON references 字段：

可选关联：
requirement_id / requirement_revision_id
test_case_id / test_revision_id
run_id / attempt_id
manifest_step_key

关系必须能证明属于同一个项目和正确链路。

原失败执行后来被重跑 PASS，
不能使 defect 自动消失或删除原失败关系。

缺陷稳定 ID、显示编号、修订 ID 分离。
显示编号并发安全分配，不使用裸 MAX()+1。

==================================================
E. 第一版状态机
==================================================

冻结一个有限状态机：

OPEN
IN_PROGRESS
RESOLVED
CLOSED
REOPENED

基本规则：

OPEN → IN_PROGRESS
OPEN → RESOLVED
IN_PROGRESS → RESOLVED
RESOLVED → CLOSED
RESOLVED → REOPENED
REOPENED → IN_PROGRESS
REOPENED → RESOLVED

不允许：
CLOSED 直接随意改写；
非法跳转；
客户端自己指定历史时间/作者。

关闭第一版要求：
缺陷至少有 RESOLVED 状态，
并且如果缺陷来自失败测试步骤，
必须有明确的后续复测 PASS 证据，
才能按本轮规则 CLOSED。

不是测试失败来源的普通缺陷，
可采用冻结的较简单关闭策略，
必须写入契约，不要留下隐式行为。

==================================================
F. 失败步骤创建缺陷
==================================================

在 M09 执行页面中：

对于 FAIL 步骤提供“创建缺陷”。

服务端接收：
runId
attemptId
stepKey
以及用户填写的缺陷内容。

服务端自己验证并取得：

- source test case
- frozen test revision
- manifest step
- requirement trace（如果存在）
- 实际结果
- 预期结果
- 当前操作者和项目

不要信任客户端提交这些派生字段。

BLOCKED 第一版可允许创建缺陷，
但与 FAIL 来源类型明确区分。

PASS 步骤不得冒充失败来源创建失败型缺陷。

创建缺陷后：
执行历史仍完全不变。

==================================================
G. 复测闭环
==================================================

第一版采用已有 M09 运行能力，不重新造 execution engine。

缺陷详情显示：
原失败 attempt / step，
相关测试用例，
已有后续 attempts。

提供“开始复测”入口：
本质上使用关联测试实例/run 的合法 rerun，
不创建第二套执行模型。

复测：
- 新 attempt 独立存在；
- 原失败 attempt 永久保留；
- PASS 后缺陷显示有可用 PASS verification；
- FAIL 后保持未验证解决；
- 一个无关测试 PASS 不能关闭该缺陷。

关闭时必须重新从服务器查询并验证
与该缺陷绑定测试链路的有效 PASS 证据，
不能信任前端传 `verified=true`。

==================================================
H. 权限
==================================================

第一版：

PROJECT_ADMIN / PROJECT_MEMBER：
- defect:read
- defect:create
- defect:update
- defect:transition
- defect:history:read

PROJECT_VIEWER：
- read/history only

租户管理员没有项目成员关系：
不自动获得缺陷访问权。

撤权/停用/租户成员失效：
旧会话下一请求立即生效。

复测仍服从 M09 权限，
不能因为 defect:update 权限获得 execution 写权限。

服务授权 + runtime RLS 双层落实。

==================================================
I. 并发、幂等、事务
==================================================

创建、编辑、状态迁移、由失败步骤创建，
全部使用持久化幂等。

同键同内容：
返回原冻结结果。

同键不同内容：
409。

重放：
重新检查当前权限。

详情提供真实 ETag。
编辑及状态迁移要求强 If-Match。

两个管理员/成员同版本修改：
一次成功，一次 412。

同一缺陷并发状态迁移：
不能产生两个互相矛盾的当前状态。

业务状态、revision、links、audit、Outbox、
idempotency 同一事务。

分别注入 audit / Outbox 失败，
验证整体回滚。

历史 revision/link 不允许普通 runtime 改写或清空。

==================================================
J. 前端
==================================================

增加缺陷工作台：

- 缺陷列表
- 查询/有界分页
- 新建
- 详情
- 编辑
- 状态流转
- 历史
- 来源测试失败信息
- 复测入口
- PASS verification
- CLOSED / REOPENED 状态

M09 页面 FAIL 步骤加入“创建缺陷”。

采用已有已经验证的状态模型：

- 请求代次
- AbortController
- 范围切换清理
- saving/loading 分离
- 失权清理
- 迟到 success/failure/finally 防护
- 412 草稿保留
- 未知写结果幂等重试
- 写成功/GET失败只重试读取

不要每个新模块重新犯以前已经修复过的
loading、草稿、幂等键和旧回包问题。

==================================================
K. 本轮验收 L01—L10
==================================================

L01：
空库及现有主线非空数据库升级通过；
需求、用例、运行、trace 数据保留。

L02：
真实 MEMBER HTTP 创建缺陷并重新读取；
显示编号、第一版 revision、audit、Outbox、
idempotency 原子一致。

L03：
编辑生成不可变新 revision；
两个并发编辑一次成功一次412；
历史不被覆盖。

L04：
状态机合法跳转通过；
非法跳转拒绝且无副作用；
CLOSED 历史不可改写。

L05：
从真实 FAIL run step 创建缺陷；
来源 run/attempt/step/test/revision 由服务端验证；
跨项目、跨租户和错误 step 拒绝。

L06：
真实 VIEWER 只读；
直接 create/update/transition 有效 CSRF 仍拒绝；
撤权/停用后的旧会话直接失权。

L07：
audit / Outbox 故障导致创建、编辑、
transition 整体回滚；
幂等无重复副作用。

L08：
原失败 attempt 保留；
从缺陷发起 rerun；
FAIL 不满足关闭条件；
相关 PASS 满足验证条件；
关闭后仍能查看原 FAIL 和 PASS 历史。

L09：
真实 Keycloak 页面完成：
MEMBER 运行测试产生 FAIL →
从失败步骤页面创建缺陷 →
打开缺陷→改为 IN_PROGRESS→RESOLVED →
从缺陷发起复测→PASS →
CLOSED →
重新打开仍能看到原 FAIL、PASS、状态历史。
独立 VIEWER 只读，直接写请求拒绝。

主要业务步骤通过真实 DOM，
API只做准备、辅助核验和越权负向测试。

L10：
最终交付 Push 与 PR merge CI 六 Job 全部通过；
新增数据库、HTTP、并发、回滚和浏览器用例
按具体测试名进入报告门禁，
失败/error/skip/missing 均不能通过。

==================================================
L. 加速执行规则
==================================================

这是本轮最重要的流程变化：

不要因为：
- 第一次 CI 失败
- 一个浏览器竞态
- 一个迁移断言错误
- 一个测试 fixture 错误
- 一个 lint/TypeScript 普通问题

就立即停止请求新指令。

在当前范围内：
定位 → 修复 → 回归 → Push → CI
可以连续进行多轮。

每次失败都保留真实记录，
但不需要每轮创建 FIX01/FIX02/FIX03 文档。

本批次只维护：
docs/progress/inputs/R09-CORE-BATCH02.md
docs/progress/runs/R09-CORE-BATCH02.md

可以在同一记录内追加：
Attempt 1
Attempt 2
Attempt 3
和对应根因、修复、CI。

只有：
范围需要改变、
需要外部不可获得输入、
存在不可逆数据风险、
或已经达到最终验收，
才停止。

==================================================
M. 最终交付
==================================================

最终返回：

1. PR #7 阶段 A 审核/修复结果及最终 SHA/CI。
2. 新 M10 分支/PR/base。
3. M10 代码 SHA、交付 SHA。
4. L01—L10。
5. 单元、PostgreSQL、HTTP、前端、浏览器测试数量。
6. 最终 Push/PR CI 和实际 checkout SHA。
7. Demo 操作路径。
8. 未实现范围和真实 BLOCKED 项。

更新并实际发布：
docs/development-status.md
docs/progress/inputs/R09-CORE-BATCH02.md
docs/progress/runs/R09-CORE-BATCH02.md
必要 ADR、契约、README、AGENTS 和报告门禁。

不要为了追写 CI SHA 循环制造文档提交。
最终 CI 结果可以在交付回复中报告。

完成整个批次后停止等待审核。
不自动合并、不部署、不恢复定时任务，
不自动进入下一模块。
