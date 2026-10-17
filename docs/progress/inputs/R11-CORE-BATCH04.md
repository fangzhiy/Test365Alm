执行 Test365Alm 加速批次：R11-CORE-BATCH04。

任务主题：

M05 附件、资产版本与安全下载第一核心切片。

本轮继续采用：

基准核验 →
连续实施 →
自审 →
自修复 →
最终交付

模式。

普通代码缺陷、测试失败、第一次 CI 红灯、fixture 问题、
对象存储/扫描器测试配置、浏览器竞态、lint/TypeScript、
迁移断言等，本轮范围内自行连续修复，不要每出现一个问题
就停止回来请求新提示词。

只有以下情况停止并报告 BLOCKED：

1. 需要用户提供真实生产对象存储、生产恶意文件扫描服务、
   客户数据或不可替代的外部授权；
2. 需要改变本消息已经冻结的资产/附件业务规则；
3. 需要破坏性改写已有正式迁移或不可逆处理已有历史数据；
4. GitHub / CI / Docker / PostgreSQL / Keycloak 等授权环境
   确实无法继续；
5. 多次定位后仍无法得到确定性结论；
6. 需要执行合并、部署、恢复定时任务、修改仓库保护、
   可见性或其他本轮未授权操作。

==================================================
A. 审核基准
==================================================

仓库：

fangzhiy/Test365Alm

已独立审核通过的 R10 最终交付：

d213e47bebbe0f89522be48c3e58003d008cdf06

R10 分支：

feat/r10-m06-001

R10 PR：

#9

审核时：

PR #9 = Open
base = chore/r01-status-001
head = d213e47bebbe0f89522be48c3e58003d008cdf06

PR #9 最终 PR checkout：

1c82824b11111d11c9491e2540036f5fddf2b923

最终 Push：
38937842395
success

最终 PR：
38937893784
success

六 Job 全部成功。

审核确认实际证据：

46 Surefire
125 Failsafe
M06 DB 12
V18→V19 非空升级 1
M06 OIDC HTTP 2
Vitest 181
Python 95
浏览器 13
isolated project UI 1
failure/error/skip = 0

正式迁移：

V19

当前故意失败探针：

V20

本消息全文原样保存到：

docs/progress/inputs/R11-CORE-BATCH04.md

先读取：

AGENTS.md
docs/development-status.md
docs/progress/inputs/R10-CORE-BATCH03.md
docs/progress/runs/R10-CORE-BATCH03.md
docs/modules/M03.md
docs/modules/M05.md

以及当前：

project/member/security
requirements
test cases
execution
defects
audit/outbox
core idempotency
RLS
当前 Maven / React / Keycloak / Testcontainers 架构
当前 migration 列表和 report gates。

先重新核对远端：

- PR #9 是否仍 Open；
- PR #9 head 是否仍为审核 SHA；
- 默认分支最新 SHA；
- 是否有人已经合并 PR #9；
- 是否存在用户新增提交；
- 是否已经存在 R11/M05 分支。

不得回退用户后来合法提交。

==================================================
B. 分支和 PR 规则
==================================================

如果 PR #9 已经由授权方合并：

从真实合并后的
chore/r01-status-001
建立：

feat/r11-m05-001

新 PR base：

chore/r01-status-001

如果 PR #9 仍未合并：

不要自行合并 PR #9。

从审核通过的：

d213e47bebbe0f89522be48c3e58003d008cdf06

建立：

feat/r11-m05-001

新 PR base：

feat/r10-m06-001

PR 描述明确：

Depends on PR #9.

只允许这一层依赖。

不要重新从旧主线单独选择 migration 版本，
否则会与 R10 的 V19 冲突。

如果 PR #9 head 已经移动：

先核对新 diff 和 CI。
不能静默把未经审核的新 R10 代码当作基线。

不强推。
不删除旧分支。
不自动合并任何 PR。
不部署。
不恢复定时任务。

==================================================
C. 本轮产品范围
==================================================

只实现 M05 第一核心切片：

1. 创建上传会话。
2. 单文件、小文件流式上传。
3. 服务端生成对象路径。
4. Quarantine 隔离存储。
5. 服务端重新计算 SHA-256。
6. 实际内容类型检测。
7. 恶意文件扫描。
8. 扫描 CLEAN 后发布为不可变 Asset Revision。
9. Asset 稳定身份与 Revision 分离。
10. 给同一 Asset 上传新版本。
11. Requirement / Test Case / Defect
    建立强类型 Attachment Link。
12. Attachment 永远冻结具体 Asset Revision。
13. 后端代理下载，每次重新授权。
14. MEMBER 上传/关联；
    VIEWER 只读/下载。
15. 基础资产/附件 Web 工作台。

本轮不实现：

- 分片上传；
- 超大文件；
- 跨租户去重；
- tenant 内内容去重；
- 删除/垃圾回收；
- retention hold；
- 法务保留；
- 对象生命周期策略；
- presigned public URL；
- CDN；
- HTML/SVG 在线预览；
- 视频转码；
- OCR；
- 邮件附件；
- M12 导入导出；
- 执行节点附件下载；
- 离线包；
- 全量附件兼容；
- 对象存储灾备；
- 多区域复制。

这是 M05 minimal slice，
不能把本轮写成完整 M05。

==================================================
D. 第一版文件限制
==================================================

冻结第一版规则。

最大单文件：

25 MiB

最大值必须：

- 服务端强制；
- 上传 session 声明时检查；
- 实际流式读取时再次检查；
- Content-Length 不能成为唯一可信依据。

第一版允许：

text/plain
application/pdf
image/png
image/jpeg

禁止：

text/html
image/svg+xml
可执行格式
脚本
以及未在 allowlist 的其他格式。

不要仅相信：

浏览器 Content-Type
扩展名
原始文件名。

实际 MIME 必须由服务端内容检测。

可以采用当前 Java 生态中稳定、受支持的检测库，
但要锁定版本并记录原因。

文件名：

只是显示元数据。

不得用于：

bucket key
filesystem path
SQL path
临时目录拼接。

文件名限制长度并清除：

CR
LF
NUL
以及会破坏 Content-Disposition 的字符。

下载一律：

Content-Disposition: attachment

并返回：

X-Content-Type-Options: nosniff

第一版不提供 inline active-content preview。

==================================================
E. 对象存储
==================================================

建立明确 storage abstraction。

例如：

BlobStore

或语义等效接口。

生产实现：

S3-compatible private object storage。

不得依赖公有 bucket。

CI / integration：

使用 Testcontainers 启动隔离的
S3-compatible 服务，例如 MinIO，
并固定明确 image/digest。

不要调用真实 AWS 或收费云资源。

至少分隔：

quarantine namespace/bucket
published namespace/bucket

对象 key 必须由服务端生成，例如：

quarantine/{tenant}/{project}/{uploadId}

published/{tenant}/{project}/{assetRevisionId}

不使用客户文件名。

同一内容不同 tenant：

本轮不做跨租户 dedup。

必须产生不同身份和对象路径。

不能通过响应、错误、时间逻辑或下载能力
泄露另一 tenant 已拥有相同 SHA-256。

==================================================
F. 恶意文件扫描
==================================================

建立明确：

MalwareScanner

或语义等效 abstraction。

生产型实现：

ClamAV-compatible clamd adapter
或已有项目最合适的真实 scanner adapter。

CI：

启动隔离扫描器容器，
使用固定 image/digest。

使用标准无害测试签名：

EICAR

验证恶意文件路径。

扫描结果最少：

CLEAN
INFECTED
ERROR

规则：

CLEAN：
允许发布。

INFECTED：
上传会话终态 REJECTED，
不能创建可下载的 published revision。

ERROR / scanner unavailable / timeout：
不能 fail-open。
保持 QUARANTINED 或明确 SCAN_FAILED 可重试状态。

不得：

扫描失败仍发布；
客户端自己提交 clean=true；
mock 一个永远 CLEAN 的生产实现后宣称安全扫描完成。

如果浏览器 CI 使用轻量测试 adapter：

可以，
但必须明确只用于浏览器 UI fixture。

真实 PostgreSQL/S3/ClamAV
必须在 server integration 中有证据。

==================================================
G. 数据模型
==================================================

至少区分：

upload_session
asset
asset_revision
scan_result
attachment_link

必要时：

asset_outbox_event

Upload Session：

- stable UUID
- tenant/project
- server-generated quarantine key
- original filename
- declared size
- declared SHA-256
- declared content type
- actual size
- computed SHA-256
- detected MIME
- state
- created_by
- timestamps
- row/version where needed

建议有限状态：

CREATED
UPLOADED
SCANNING
PUBLISHED
REJECTED

或等效明确状态。

不能从：

REJECTED → PUBLISHED

偷偷反转。

scanner transient ERROR：

采用可重试、明确状态，
不要与 INFECTED 混为一谈。

Asset：

- stable UUID
- tenant/project
- current_revision_id
- created_by
- timestamps

Asset Revision：

- stable UUID
- asset_id
- revision number
- object key
- size
- SHA-256
- detected MIME
- original filename
- scan_result reference
- created_by
- created_at

发布后的 asset_revision：

immutable。

不能：

UPDATE object key
UPDATE hash
UPDATE size
换 scanner result
删除历史 revision

普通 runtime 无 UPDATE/DELETE/TRUNCATE 历史权限。

新版本：

新增 asset_revision，
再显式推进 asset.current_revision_id。

旧 revision 保留。

==================================================
H. 新版本行为
==================================================

第一版支持：

给已有 Asset 创建一个新上传会话，
成功扫描后产生：

revision N+1

并更新：

asset.current_revision_id。

旧 Attachment Link：

继续指向原 asset_revision_id。

不能自动漂移到新版本。

真实测试：

Asset rev1 →
建立 Requirement Attachment →
上传 rev2 →
Requirement 原 Attachment 仍下载 rev1。

如果需要用户改成 rev2：

本轮可通过新增另一 attachment link 完成。

不自动替换旧 link。

==================================================
I. Attachment Link
==================================================

第一版支持目标：

REQUIREMENT
TEST_CASE
DEFECT

使用强类型关系。

不要做：

targetType + arbitrary JSON payload

然后完全没有数据库约束。

可以采用：

统一 attachment_link + typed nullable FK

或拆成多个 link 表。

必须验证：

tenant
project
target entity
asset
asset revision

均属于同一个项目。

Attachment Link 至少保存：

id
tenant/project
kind
target stable ID
asset_id
asset_revision_id
created_by
created_at

Link 一旦创建：

冻结 asset_revision。

本轮不实现 unlink/delete，
除非当前实现天然需要并且可以安全完成。

如果不实现删除：

UI 明确只提供添加/读取，
不要伪造删除按钮。

对 soft-deleted requirement：

禁止建立新 attachment link。

已经存在的历史 link：

保留，
不静默删除。

==================================================
J. 上传协议
==================================================

建议 HTTP：

POST
/api/v1/projects/{projectId}/uploads

body：

{
  "filename": "...",
  "size": ...,
  "sha256": "...",
  "contentType": "...",
  "assetId": optional
}

assetId 缺失：

准备创建新 Asset。

assetId 存在：

准备给已有 Asset 创建下一 Revision。

返回：

uploadId
state
size/hash metadata

上传内容：

PUT
/api/v1/projects/{projectId}/uploads/{uploadId}/content

raw body。

要求：

- CSRF；
- project authorization；
- session owner/scope；
- 真实流式上限；
- hash；
- size；
- content type detection；
- quarantine write。

相同 uploadId：

完全相同内容重试：
返回同一已确认 receipt，
不重复制造 revision。

不同内容：
409。

完成：

POST
/api/v1/projects/{projectId}/uploads/{uploadId}/complete

body：
{}

执行：

确认 quarantine object →
scan →
CLEAN 时 publish →
DB transaction 创建 revision / 更新 asset →
audit / Outbox / idempotency →
返回冻结结果。

扫描 ERROR：

不生成 published revision，
允许明确重试 complete。

INFECTED：

明确终止；
不能通过换幂等键重新 complete 成 CLEAN，
除非重新创建一个新 upload session 并上传新内容。

读取：

GET upload status
GET asset
GET asset revisions（有界分页）

==================================================
K. 下载
==================================================

使用：

backend proxy download

本轮不发长期或公开 presigned URL。

例如：

GET
/api/v1/projects/{projectId}/assets/{assetId}/revisions/{revisionId}/download

每一次下载：

重新验证当前用户项目权限。

撤权、
tenant membership 失效、
principal disabled：

旧登录 session 下一次下载立即拒绝。

必须验证：

asset / revision / project
强归属。

不能只因为知道：

revision UUID
object key

就下载。

响应至少：

Content-Type = server detected MIME
Content-Disposition = attachment; safe filename
X-Content-Type-Options = nosniff
Content-Length

不要把内部 bucket/key 暴露给浏览器。

VIEWER：

允许读取/下载已发布附件。

VIEWER：

不能上传/创建版本/创建 Attachment Link。

==================================================
L. 外部存储和数据库一致性
==================================================

不要假装 PostgreSQL 和 S3 有 2PC。

明确设计失败状态与补偿。

安全原则：

任何部分失败时，
绝不能让未确认/未 CLEAN 文件通过业务下载。

至少验证：

1. quarantine PUT 成功，
   DB receipt 更新失败：

   重试使用同一 uploadId/key；
   不产生两个 asset revision；
   没有业务下载路径。

2. 扫描 CLEAN，
   published object copy/write 成功，
   DB publish transaction 失败：

   published orphan object 即使存在，
   也不能通过业务 API 下载，
   因为没有授权 DB revision。

3. DB publish 成功，
   quarantine cleanup 失败：

   已发布 revision 可正常工作；
   quarantine 残留记录为 cleanup pending/
   可诊断状态，
   不影响权限。

本轮不必实现完整垃圾回收 daemon，
但必须：

- 可识别 orphan/cleanup pending；
- 有确定性测试；
- 文档明确后续 GC 工作。

对象存储失败、scanner timeout
不能变成伪造的成功 DB 行。

==================================================
M. 幂等与并发
==================================================

以下命令使用持久化幂等：

create upload session
complete upload
create attachment link
create next asset version session

同键同内容：

冻结重放原结果。

同键不同内容：

409。

重放：

重新鉴权。

Content PUT：

使用 uploadId + 声明 SHA/size
形成确定性内容身份。

并发完全相同 PUT：

只有一个有效 quarantine object / receipt。

并发 complete：

最多创建：

一个 Asset
一个 Asset Revision
一个 Scan Result 的有效发布结果
一个相关 attachment candidate

不能 revision number 重复。

两个并发新版本：

revision number 唯一递增，
不得 MAX()+1 裸竞争。

如果使用 allocator/current pointer lock：

通过真实 PostgreSQL 并发验证。

==================================================
N. 权限
==================================================

第一版权限：

PROJECT_ADMIN / PROJECT_MEMBER：

asset:read
asset:upload
asset:version:create
attachment:read
attachment:create
asset:download

PROJECT_VIEWER：

asset:read
attachment:read
asset:download

无：

upload
version create
attachment create

TENANT_ADMIN
如果没有项目 membership：

不自动访问资产。

下载与 metadata
都遵守项目范围。

不能产生：

“页面隐藏上传，但直接 HTTP 可以写”的差异。

应用授权 + runtime RLS 双层执行。

==================================================
O. 数据库 / RLS
==================================================

首先查看真实 migration 最大版本。

如果 R10 PR #9 尚未合并，
本分支已经基于 R10 head，
所以新正式 migration：

从 V20 之后寻找第一个正式可用版本。

注意：

R10 的 V20 是 intentional failure probe，
不是正式生产 migration。

不要覆盖。

根据当前测试目录实际结构，
将 intentional failure probe
移动到下一个未占用测试版本。

如果 PR #9 已合并：
同样从真实最新正式版本和 failure probe 计算。

不猜版本。

正式 migration additive。

不得修改已经发布的 V1—V19。

RLS 验证：

同 tenant 不同 project
跨 tenant
VIEWER write
非成员
撤权
disabled
tenant membership expiry

runtime：

不能直接读取别项目 asset metadata；
不能 direct SQL 改写 asset_revision；
不能把 foreign asset revision 绑定当前 target；
不能把 quarantine/rejected 内容伪造成正常 attachment。

==================================================
P. Audit / Outbox
==================================================

至少记录：

UPLOAD_SESSION_CREATED
UPLOAD_CONTENT_CONFIRMED
UPLOAD_SCAN_CLEAN
UPLOAD_SCAN_REJECTED
ASSET_CREATED
ASSET_REVISION_PUBLISHED
ATTACHMENT_LINK_CREATED

如果 transient scan error：

记录必要诊断状态，
不要把每次内部重试制造成用户通知事件。

Audit / Outbox：

服务器时间、服务器 actor。

不接受客户端指定。

事件 payload：

只保留必要 ID / revision / hash 前必要部分 /
size / MIME / state。

不要写：

原始文件内容
凭据
scanner socket
bucket secret
完整恶意内容。

==================================================
Q. 事务故障注入
==================================================

在真实隔离环境分别验证：

audit INSERT failure
Outbox INSERT failure

至少覆盖：

publish asset revision
create attachment link

要求：

数据库业务状态整体回滚。

对象存储不是 DB transaction：

按 L 节状态机处理，
不能因 DB rollback 声称对象存储自动 rollback。

故障后恢复：

同一明确 intent
可以安全恢复或安全重试，
不重复 revision/link。

==================================================
R. 前端
==================================================

增加最小资产/附件工作台。

优先集成到：

Requirement
Test Case
Defect

详情页面。

可以复用一个：

AttachmentsPanel

用户操作：

选择文件 →
显示文件名、大小、声明类型 →
创建 session →
上传 →
扫描状态 →
CLEAN 后显示附件 →
下载。

新 Asset 版本：

资产详情中提供：

上传新版本。

附件显示：

文件名
revision
size
detected MIME
SHA-256 简写
scan status
创建时间

VIEWER：

只显示附件与下载。

不要显示：

上传
新版本
创建 link。

状态必须明确：

正在上传
已上传待扫描
扫描中
扫描失败可重试
恶意文件已拒绝
已发布
结果未知

未知 POST/complete：

保留同一 idempotency intent。

上传二进制 PUT：

不能因 UI 刷新自动重新创建 upload session。

项目切换/失权：

中止旧上传读取请求；
清理旧项目文件名、asset/link metadata；
已发到服务器的 PUT 如果结果未知，
保留明确恢复入口，
不要假装没发生。

==================================================
S. 浏览器验收
==================================================

新增一个真实 Keycloak DOM M05 流程。

普通 MEMBER：

登录 →
选择项目 →
创建 Requirement 或使用已有 Requirement →
从详情选择 benign text 文件 →
创建 upload session →
上传 →
完成扫描/发布 →
创建 attachment link →
刷新页面 →
附件仍存在 →
通过 UI 下载。

再上传该 Asset 的 revision 2：

确认：

当前 Asset = rev2

但原 Requirement Attachment
仍冻结 rev1。

独立 VIEWER：

登录 →
读取 Requirement →
看到附件 →
可下载 rev1 →
没有上传/新版本/关联入口。

用有效 CSRF 直接请求：

create upload
complete
attachment create

均拒绝 403。

EICAR / scanner error
主要由 server integration 验证，
浏览器不用真的下载恶意测试文件。

DOM 负责业务主流程，
API 只用于 fixture、辅助断言和直接负向权限检查。

browser：

single worker
zero retries。

==================================================
T. 本轮验收 N01—N10
==================================================

N01 — migration / upgrade

空库迁移成功。

真实非空上一版数据库升级成功。

原有：

Requirement
Test
Execution
Defect
Trace
Release/Cycle/Snapshot
idempotency/audit

均保持。

M05 新表为空起步，
不改写旧业务数据。

N02 — upload integrity

真实 MEMBER：

创建 session →
PUT benign 文件 →
服务端计算 size/hash →
实际 MIME 检测正确。

声明 size/hash/MIME 不匹配：

明确拒绝且无 published revision。

超 25 MiB：

流式拒绝。

文件名不能影响 object path。

N03 — quarantine / scan

真实 MinIO/S3-compatible quarantine。

真实 ClamAV-compatible scan。

benign：
CLEAN → publish。

EICAR：
INFECTED → REJECTED。

scanner unavailable/timeout：
不发布；
保持安全可恢复状态。

任何非 CLEAN：

下载 API 不可获得内容。

N04 — immutable version

Asset rev1 发布并建立 attachment。

上传 rev2。

current revision 变 rev2。

旧 attachment 仍引用/下载 rev1。

runtime 无法 UPDATE/DELETE/TRUNCATE rev1。

N05 — typed links

Requirement
Test Case
Defect

三类合法 attachment link 通过。

同项目错误 ID、
跨项目、
跨 tenant、
foreign asset revision：

拒绝且无副作用。

soft-deleted Requirement：
不能建立新 link。

N06 — permissions

MEMBER：
上传/关联/下载通过。

VIEWER：
metadata/download 通过，
upload/version/link 403。

无项目 membership tenant admin：
不可访问。

撤权 / disabled / tenant expiry：

旧 session 下一次下载和 metadata 请求立即失权，
无需先 /me。

N07 — retry / concurrency

同键 create/complete/link：

冻结重放，无重复 side effect。

同键异内容：

409。

并发 identical PUT：
单一 receipt。

并发 complete：
单一 published revision。

并发 next revision：
revision number 唯一。

N08 — external consistency / rollback

quarantine success + DB failure
不产生可下载 asset。

publish object success + DB rollback
orphan object 不可通过业务 API 下载。

DB commit + quarantine cleanup failure：
发布内容可用，
cleanup 状态可诊断。

audit/outbox failure：
DB asset/link 原子回滚。

恢复后相同 intent 安全完成。

N09 — real browser

真实 Keycloak MEMBER：

Requirement →
benign upload →
CLEAN publish →
attach →
reload →
download →
new asset revision →
旧 attachment 仍 rev1。

独立 VIEWER：

可读/下载，
无写 UI，
有效 CSRF 写接口 403。

N10 — CI / evidence

最终交付 Push 与 PR：

现有六 Job 全部 success。

不删除旧 gate。

新增：

M05 real DB/storage/scanner report gate
non-empty upgrade gate
M05 HTTP security gate
M05 frontend exact-name gate
M05 browser exact-name gate

missing
failure
error
skip
duplicate

均不能通过。

==================================================
U. 测试实现要求
==================================================

Server integration 至少：

AssetDatabaseIT
或等效：

真实 PostgreSQL
真实 S3-compatible container
真实 ClamAV-compatible container

明确固定 image/digest。

测试禁止访问真实客户/生产存储。

增加：

AssetMigrationUpgradeIT

使用真实非空上一版数据。

增加真实 HTTP/OIDC：

严格 JSON
CSRF
role
project scope
download revocation
wrong MIME/hash/size
idempotency
typed link

不要用：

mock repository
@WithMockUser
纯 service test

替代 HTTP 验收。

前端 deterministic tests 至少覆盖：

项目切换
上传中切项目
upload timeout
complete timeout
late success
late failure
late abort
失权
unknown result
GET recovery
StrictMode
unmount
rev1/rev2 frozen link
VIEWER readonly

==================================================
V. CI 资源边界
==================================================

保持已有六 Job：

planning-tools
web
server
readiness-recovery
migration-failure
oidc-browser

不增加付费外部 CI 服务。

S3-compatible / ClamAV：

优先由 server integration Testcontainers
启动。

浏览器环境如果真实启动两者代价合理：
可复用真实服务。

如果 browser job 使用 CI-only adapter：

必须：

- 明确标记 TEST ONLY；
- 不能进生产 profile；
- 不允许 fail-open；
- server integration 仍必须有真实 MinIO/S3 + ClamAV 证据。

如果 ClamAV image 首次启动导致 CI 时间问题：

允许在本轮优化镜像/pinning/healthcheck，
不要通过移除真实扫描测试获得绿色。

==================================================
W. 安全自审
==================================================

提交前逐项检查：

1. path traversal
2. CRLF filename
3. content-type spoof
4. hash mismatch
5. truncated body
6. oversized stream
7. EICAR
8. scanner timeout
9. scanner unavailable
10. quarantine object direct exposure
11. published orphan
12. cross-project asset UUID
13. cross-tenant same SHA
14. rev1 link after rev2
15. stale session download
16. VIEWER direct upload
17. duplicate complete
18. concurrent new version
19. object store credential logging
20. presigned/public URL accidental暴露
21. audit/outbox payload accidentally包含文件内容
22. temp file cleanup
23. browser object URL cleanup（如果使用）
24. multipart/form parser resource exhaustion
25. Content-Disposition filename safety

发现普通问题：

直接修复、回归、继续。

==================================================
X. 文档和证据
==================================================

更新并发布：

docs/development-status.md
docs/progress/inputs/R11-CORE-BATCH04.md
docs/progress/runs/R11-CORE-BATCH04.md

新增/更新：

contracts/assets.md

新增 ADR：

使用当前下一个未占用编号。

ADR 说明至少：

- S3-compatible private storage；
- quarantine/published；
- scanner fail-closed；
- immutable revision；
- proxy download；
- no cross-tenant dedup；
- external-store / DB 非 2PC 处理。

README / AGENTS：
按实际需要更新。

记录：

所有真实红 CI、
根因、
修复 SHA、
最终 CI。

不删除失败历史。

不要为了把最终 CI SHA 写进文档
无限制造 documentation commit。

==================================================
Y. 最终交付
==================================================

最终回复必须给出：

1. 实际起点和 PR #9 当时状态。
2. 新 branch。
3. 新 PR / base / dependency。
4. 代码验证 SHA。
5. 最终交付 SHA。
6. 新正式 migration 版本。
7. 新 intentional failure probe 版本。
8. 对象存储实现及 CI image/digest。
9. scanner 实现及 CI image/digest。
10. N01—N10：
    PASS / FAIL / BLOCKED + 具体证据。
11. Surefire 数量。
12. Failsafe 总数。
13. M05 专项真实 DB/storage/scanner 数量。
14. non-empty upgrade 数量。
15. HTTP/OIDC 数量。
16. Vitest 数量。
17. Python 数量。
18. browser 数量。
19. Push CI run。
20. PR CI run。
21. PR merge-ref checkout SHA。
22. MEMBER demo 路径。
23. VIEWER demo/deny 路径。
24. 真实扫描 EICAR 结果。
25. 外部存储故障/DB rollback 结果。
26. 本轮真实红 CI 和自行修复记录。
27. 剩余 M05 范围。
28. BLOCKED 项；没有则 NONE。

明确：

PR 未自动合并。
未部署。
未恢复定时任务。
未修改默认分支/保护/可见性。
未开始 M12 或其他下一模块。

达到验收或真实 BLOCKED 后停止，
等待独立审核。
