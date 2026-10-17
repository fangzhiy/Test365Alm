执行 Test365Alm 任务：R11-CORE-BATCH04-FIX01。

目标：

在现有 PR #10 上完成 M05 最小切片最后三个边界：

1. 资产 current revision / revision allocator 的数据库单调完整性；
2. terminal upload complete 的冻结幂等语义；
3. V19/V20 升级后 scanner 数据库身份的正式可复用 provision 路径。

保留 R11 已经真实通过的全部实现和测试。
不要重新开发整套附件系统。
不要进入 M12 或其他模块。

==================================================
A. 当前审核基准
==================================================

仓库：

fangzhiy/Test365Alm

分支：

feat/r11-m05-001

PR：

#10

base：

feat/r10-m06-001

依赖：

PR #10 depends on PR #9。

审核最终 head：

c150958d964b85370393373516e377465ceb7ce2

审核 PR merge checkout：

1b6993441c442890b0a5ee85a5d58c2fa7e5b6aa

当前正式 migration：

V20

当前 intentional migration failure probe：

V21

审核确认最终制品：

Surefire 63
Failsafe 143
AssetDatabaseIT 15
AssetMigrationUpgradeIT 1
OIDC class 23，其中 M05 专项 2
Vitest 213
Python 106
Playwright 14
isolated project UI 1

全部 failure/error/skip = 0。

真实 S3-compatible / scanner：

SeaweedFS 4.48 fixed digest
ClamAV 1.5.4 fixed digest

本消息全文原样保存到：

docs/progress/inputs/R11-CORE-BATCH04-FIX01.md

继续现有：

feat/r11-m05-001

继续 PR #10。

不要创建 PR #11。

不要合并 PR #9/#10。
不要部署。
不要恢复定时任务。
不要修改默认分支、保护规则或仓库可见性。

如果远端已经出现用户后续提交：
先核对并保留，不回退、不强推。

==================================================
B. F01 — 修复 asset allocator/current pointer 数据库完整性
==================================================

审核发现：

V20 允许 runtime：

UPDATE asset
SET next_revision_no = ...

同时：

asset_current_guard

只在 UPDATE OF current_revision_id 时运行，
并通过：

revision_no = next_revision_no - 1

判断 current pointer。

因为 next_revision_no 本身可先被 runtime 修改，
当前保护的依据不是不可变的。

现有测试
runtimeCannotForgeRevisionFromUnscannedOrRejectedUpload
甚至为了构造负向场景实际执行：

UPDATE asset SET next_revision_no=2

说明该列目前确实可由受限 runtime 直接改变。

先用真实 PostgreSQL 复现，不要只静态修改代码。

测试场景：

1. MEMBER 正常发布同一 Asset：
   rev1
   rev2

2. 确认：
   current_revision_id = rev2
   next_revision_no = 3

3. 使用合法 MEMBER 上下文的
   test365alm_runtime 直接 SQL。

4. 尝试：
   - 将 next_revision_no 调小；
   - 将 next_revision_no 任意跳大；
   - 在同事务把 next_revision_no 调到与 rev1 匹配，
     再把 current_revision_id 指回 rev1；
   - 单独把 current_revision_id 指回旧 revision；
   - 制造 revision number gap。

5. 每一种都必须在数据库边界被拒绝，
   不能只依靠 Service 不调用这种 SQL。

6. 正常 service：
   rev3
   rev4
   并发新版本
   仍必须正常工作且编号连续唯一。

推荐的最低完整性不变量：

对每个已经发布 Asset，在事务提交时：

next_revision_no =
MAX(asset_revision.revision_no) + 1

并且：

current_revision_id
必须指向该 Asset 最大 revision_no 的 revision。

不能允许：

current pointer 回退；
allocator 回退；
allocator 任意跳号；
没有 revision 的 allocator advance。

第一版不支持“将 current revision 手动切回旧版本”。

如果确实需要未来 rollback：
另设计显式业务命令，
本轮不要借数据库直接 UPDATE 实现。

实现方式可选择：

- DEFERRABLE / INITIALLY DEFERRED constraint trigger；
- 或受控数据库函数/等效方案。

优先选择最小、可审计方案。

如果使用 SECURITY DEFINER：

必须：

- schema-qualified 对象；
- 安全 search_path；
- 最小 EXECUTE grant；
- 自己重新验证 tenant/project/principal；
- 不让 runtime 任意调用 owner 权限操作其他对象。

不要使用一个任何 runtime 都能自己 set 的 GUC
作为唯一授权证明。

不要修改 V20。

新增正式 migration：

V21

当前 intentional failure probe：

从 V21 移到 V22。

同步所有 migration count / upgrade / failure-gate。

必须验证：

V20 非空资产数据 →
V21

升级后：

rev1/rev2
current pointer
next revision
attachment
scan_result
upload
object identity

全部保持。

==================================================
C. F02 — terminal complete 必须冻结同 key 结果
==================================================

原 R11 冻结规则：

create upload
complete upload
create attachment link

都采用持久化幂等。

同：

project
principal
route
Idempotency-Key
request content

重复：

必须重放原操作的冻结 outcome。

不能因为资源后来进入终态，
让同一个 key 获得另一种业务结果。

当前感染流程：

第一次同 key：

real clamd → INFECTED
upload → REJECTED
HTTP 422 FILE_INFECTED

但 rejectInfected 没有保存 complete 的幂等 outcome。

第二次相同 key：

replay miss
看到 state=REJECTED
→ 409 UPLOAD_REJECTED

这是本轮要修的明确契约偏差。

冻结规则如下：

1. 第一次 complete 检出 INFECTED：

   终态必须：
   REJECTED

   返回：
   422 FILE_INFECTED

2. 同一 complete key + 同一 `{}` 再请求：

   重新鉴权。

   如果仍有权限：
   必须重放原冻结 terminal outcome：

   422 FILE_INFECTED

   code/message/status 与原 terminal outcome 一致。

   不重新扫描。
   不新增 audit。
   不新增 Outbox。
   不新增 scan_result。
   不修改 upload row_version。

3. 新的 complete key 对已 REJECTED upload：

   可以继续使用当前契约：

   409 UPLOAD_REJECTED

   因为它是一个新的业务意图，
   而不是原 key 的 replay。

4. 同 key 不同 request hash：

   409 IDEMPOTENCY_KEY_REUSED。

5. 重放前重新鉴权：

   如果主体已经撤权/disabled/tenant expiry：

   不得因为有 terminal outcome 而返回旧敏感结果。

6. SCAN_UNAVAILABLE / timeout：

   不是终态。

   当前要求继续保留：

   SCAN_FAILED

   相同 key 可在 scanner 恢复后明确重试，
   最终 CLEAN 时可以成功 PUBLISHED。

不要把 transient scanner failure
错误地冻结成永久 503。

7. 成功 PUBLISHED 的原有 frozen replay
继续保持不变。

实现可以：

扩展 core idempotency 支持冻结业务 error outcome，

或者使用 M05 专属、受约束的 terminal command outcome。

但不能：

只在内存记；
根据当前 state 临时猜旧错误；
把整个 exception stack 保存到数据库。

持久化至少需要：

HTTP status
business code
safe message / outcome kind

且必须绑定：

tenant
project
principal
route
key
request hash
expiry

不得泄漏内部异常。

如果扩展 core_command_idempotency：

必须确保旧模块的现有成功 replay
完全回归通过。

==================================================
D. F03 — 统一 scanner 数据库身份的升级 provision 路径
==================================================

审核发现两个现实路径不同：

fresh compose 初始化：

r03_init_db.sh
可创建：

test365alm_scanner LOGIN PASSWORD ...

但已有 V19 数据库升级 V20 时：

V20 migration 只会在角色不存在时创建：

test365alm_scanner NOLOGIN

升级测试之所以可真正扫描，
是测试辅助代码另外创建：

LOGIN child role
IN ROLE test365alm_scanner。

这证明代码能力存在，
但正式仓库没有一个面向真实升级环境的
可复用 scanner identity provision 流程。

本轮不要在 migration 内写密码。

新增一个明确、可重复执行的运维工具，
例如：

tools/provision_asset_scanner_role.sh

或等效脚本。

要求：

1. 由数据库 owner / authorized operator 执行。
2. 密码通过：
   environment / psql variable / stdin-safe secret mechanism
   传入。
3. secret 不出现在：
   shell trace
   stdout
   logs
   committed files。
4. 推荐角色结构：
   group capability role：
   test365alm_scanner

   dedicated LOGIN role：
   独立 scanner login
   IN ROLE test365alm_scanner

   不要求应用 scanner 登录必须等于 group role 名称。

5. 必须兼容：
   - fresh V20 DB；
   - V19→V20→V21 upgraded DB；
   - 已经存在 test365alm_scanner LOGIN 的当前开发数据库。

   不破坏已有可工作的测试/开发环境。

6. 重复运行 provisioning：
   幂等或明确安全拒绝；
   不扩大权限。

7. LOGIN role 必须：
   NOSUPERUSER
   NOCREATEDB
   NOCREATEROLE
   NOBYPASSRLS

8. 只能继承 scanner group 能力；
   不加入 runtime role；
   不获取 owner 权限。

9. AssetConfiguration 继续使用显式：

TEST365ALM_ASSETS_SCANNER_DB_USERNAME
TEST365ALM_ASSETS_SCANNER_DB_PASSWORD

不得从 runtime datasource 密码回退。

10. 增加真实 upgrade test：

从非空 V20 数据库
应用 V21 →
调用仓库正式 provision 工具的等效路径 →
使用这个 LOGIN identity →
真实 clamd CLEAN attestation →
正常完成 asset publish。

不能再仅调用：
AssetTestInfrastructure.provisionScannerDatabase()
然后声称正式 upgrade provisioning 已验证。

测试 helper 可以内部复用正式 provision 逻辑，
但正式工具必须是真实来源。

更新：

README
AGENTS
contracts/assets.md
ADR025

明确：

Flyway 不管理 scanner secret；
role capability 与 LOGIN secret provisioning
是两个不同职责。

本轮不实际配置任何生产数据库。

==================================================
E. 补充 DB 防线验收
==================================================

在 AssetDatabaseIT 或新的专属 suite 中增加精确测试：

1.
publishedAssetAllocatorAndCurrentPointerCannotMoveBackwardOrGap

2.
infectedCompleteSameKeyReplaysFrozenTerminalRejection

3.
infectedCompleteNewKeyReturnsAlreadyRejectedWithoutRescan

4.
scannerTransientFailureSameKeyRemainsRetryable

5.
upgradedDatabaseUsesProvisionedRestrictedScannerLogin

名称可调整，但 report gate 使用最终精确名称。

对 pointer 测试必须使用：

真实 test365alm_runtime
合法 MEMBER context
真实已发布 rev1/rev2

不能用 VIEWER 权限拒绝代替完整性约束。

对 scanner identity 测试必须检查：

current_user
rolsuper=false
rolbypassrls=false
rolcreaterole=false
不是 runtime role
具备 scanner group membership

并证明：

能写合法 scan attestation；
不能写 foreign project；
不能写没有 confirmed upload 的 CLEAN。

==================================================
F. 保留已有真实 S3 / ClamAV 证据
==================================================

不要替换掉：

SeaweedFS real S3-compatible integration
真实 clamd INSTREAM
EICAR
scanner pause/resume
scanner refused port
scanner timeout
published orphan
quarantine orphan
cleanup_pending
rev1/rev2 frozen attachment
typed links
RLS
OIDC
browser。

不通过 mock/fake scanner
让 FIX 变绿。

最终 server integration
继续启动真实隔离：

PostgreSQL
S3-compatible
ClamAV。

==================================================
G. `/me=401` 间歇失败处理
==================================================

历史运行：

38946999225

出现独立登录 `/me=401`。

目前根因未证明。

保留这条历史。

本轮：

不要：

增加 Playwright retry；
增加登录 retry；
提高 timeout；
改认证模型；
删除失败记录。

如果本轮自然再次出现：

收集现有脱敏诊断并确定性定位。

如果没有再次出现：

继续写：

“未复现 / 根因未证明”

不要声称已经修复。

这不是 FIX01 的完成阻断，
除非最终必需 CI 无法稳定通过。

==================================================
H. Migration 和升级
==================================================

正式：

V21

failure probe：

V22

不修改：

V1—V20。

升级 fixture 必须包含真实非空 M05 数据：

upload PUBLISHED
asset
rev1
rev2
scan_result
attachment
audit
Outbox
idempotency

升级 V20→V21 后逐字段确认：

旧数据保持；
对象 key 不变化；
hash/size/MIME 不变化；
current pointer 保持 rev2；
next revision 保持 3；
旧 attachment 仍 rev1。

然后发布 rev3，
必须获得 revision number 3。

==================================================
I. CI / report gate
==================================================

继续现有六 Job：

planning-tools
web
server
readiness-recovery
migration-failure
oidc-browser

保留所有 R11 既有 gate。

更新：

verify_r11_asset_report.py

精确要求本 FIX 新增的：

allocator/current pointer DB test
terminal rejection replay test
upgrade scanner provision test

missing
duplicate
failure
error
skip

任一不能通过。

migration-failure：

验证正式 V21 成功，
intentional V22 精确失败。

不要只改文字提示，
实际工具和测试必须移动。

==================================================
J. 自审
==================================================

提交前专门检查：

1. rev2 是否可通过 runtime SQL 回到 rev1。
2. next_revision_no 是否可任意 +100。
3. next_revision_no 是否可调小。
4. DB transaction 结束时：
   current 是否一定为最大 revision。
5. revision 编号是否连续。
6. 并发 rev3/rev4 是否仍唯一。
7. INFECTED same key 是否两次都是相同 422 outcome。
8. INFECTED new key 是否 409 且不重新 scan。
9. SCAN_FAILED same key 是否仍能恢复成功。
10. terminal outcome replay 是否先重新鉴权。
11. scanner LOGIN role 是否不是 runtime/owner。
12. upgrade 后不依赖 Test helper 才能 provision scanner。
13. secret 是否出现在日志或证据制品。
14. V21 是否保持所有 V20 数据。
15. R10/R09/R08 旧 gate 是否仍通过。

发现普通问题：

直接修复并继续。

==================================================
K. 本轮验收 F01—F05
==================================================

F01：
真实 PostgreSQL 证明 published asset 的
allocator/current pointer 不能回退或跳号；
正常 rev3 / 并发新版本继续通过。

F02：
INFECTED terminal complete
同 key 同请求冻结重放原 422 FILE_INFECTED；
新 key 返回 UPLOAD_REJECTED；
transient SCAN_FAILED 同 key 可恢复。

F03：
已有数据库升级后使用正式 repository provisioning 路径
创建最小 scanner LOGIN identity；
真实 CLEAN scan/publish 成功；
权限最小且无 secret 泄漏。

F04：
V20→V21 非空资产升级，
rev1/rev2、attachment、scan、hash/object identity、
audit/outbox/idempotency 完整保持；
之后 rev3 连续发布。

F05：
最终交付 Push / PR 六 Job success；
精确 report gate 已包含本 FIX 场景；
零 failure / error / skip。

==================================================
L. 文档
==================================================

发布：

docs/progress/inputs/R11-CORE-BATCH04-FIX01.md
docs/progress/runs/R11-CORE-BATCH04-FIX01.md

更新：

docs/development-status.md
contracts/assets.md
ADR025
README
AGENTS

仅按实际需要。

保留：

R11 原执行记录
原红 CI
38946999225 `/me=401`
scanner 首轮恢复失败

不得删改成“从未发生”。

==================================================
M. 最终返回
==================================================

最终回复必须包含：

1. 分支 / PR / base。
2. 开始 SHA。
3. 代码验证 SHA。
4. 最终交付 SHA。
5. V21 migration。
6. V22 failure probe。
7. pointer/allocator 原问题的真实复现结果。
8. 修复后 DB 负向结果。
9. INFECTED 同 key 第一次/第二次的
   HTTP status + business code。
10. INFECTED 新 key结果。
11. SCAN_FAILED 原 key恢复结果。
12. scanner provision 工具和角色模型。
13. upgrade 后 scanner current_user / role flags。
14. V20→V21 nonempty asset upgrade 结果。
15. Surefire。
16. Failsafe 总数。
17. M05/FIX 专项数量。
18. OIDC HTTP。
19. Vitest。
20. Python。
21. browser。
22. Push CI run。
23. PR CI run。
24. PR actual checkout / merge-ref SHA。
25. F01—F05 mapping。
26. 本轮新出现的真实失败及修复。
27. `/me=401` 是否再次复现；
    没有则明确写未复现、根因仍未证明。
28. BLOCKED；
    无则 NONE。

明确：

PR #10 仍未合并。
PR #9 不自动合并。
未部署。
未恢复定时任务。
未修改默认分支/保护/可见性。
未开始 M12。

完成后停止，
等待独立审核。
