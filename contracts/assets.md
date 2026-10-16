# M05 R11 — 隔离上传、不可变资产版本与附件

本契约仅为 R11 最小切片；依赖已审核 R10 的项目授权、严格 JSON、受限 runtime、审计/Outbox 和持久化命令幂等。不等于完整 M05、生产安全认证或旧 ALM 兼容。

## 信任边界与权限

- 浏览器声明、raw body、文件名、MIME、SHA 均是不可信输入。文件名只作显示，不参与对象路径。对象存储、扫描器的失败和响应也必须验证。
- PROJECT_ADMIN/MEMBER 可上传、创建版本/关联；VIEWER 只读/下载。非项目成员的 TENANT_ADMIN 无自动权限。
- 上传会话只能由创建主体读取/PUT/complete；资产及附件由当前项目读权限控制。每次操作及幂等重放重新授权，撤权/停用/租户成员失效在下一请求生效。
- 对象存储为 private S3-compatible，分别使用 quarantine/published namespace；客户端不接触 bucket/key、存储凭据或 presigned URL。无跨租户或租户内去重。
- 正式迁移仅追加；R11原切片正式V20。FIX01追加V21资产提交完整性约束，故意失败探针移V22，两者物理位置分离，不改写V1—V20。实际验证见本轮记录，不能把新契约本身作为验收证据。

## 文件规则

- 大小 1..26214400 bytes（25MiB），声明和实际流式读取双重限制。即使无 Content-Length、伪造或截断也不能通过。
- 声明sha256为64位小写十六进制，服务端重新计算；实际size/hash必须相同。
- 允许且必须实际检测相符：`text/plain`、`application/pdf`、`image/png`、`image/jpeg`。不使用客户端文件名作检测提示；HTML/SVG/脚本/可执行及未知类型拒绝。检测不是完整沙箱，真实扫描仍必需。
- filename 1..200字符；去除CR/LF/NUL、路径分隔及可能破坏Content-Disposition的引号等控制字符，清理后为空拒绝。它不决定SQL/临时目录/对象key。
- S3与scanner各自有有限超时；HTTP上传配置独立的读空闲预算，不把多个独立预算相加后的行为说成一个墙钟总上限。raw PUT不使用multipart解析；临时文件由本请求创建且finally删除，不打印内容。
- 应用显式设置 `spring.servlet.multipart.enabled=false`，不注册 multipartResolver 或 Servlet multipart 配置；raw PUT 的 `multipart/form-data`（包括畸形边界）按声明类型不匹配返回 422/CONTENT_MISMATCH，不先交给 multipart 解析器缓冲或落盘。

## 数据与状态

稳定Asset身份与追加AssetRevision分离。修订绑定发布对象、真实size/hash/MIME和不可变CLEAN扫描结果。runtime不能INSERT扫描结果，也不能UPDATE/DELETE/TRUNCATE历史修订/附件/扫描结果；真实扫描证明由专用受限scanner数据库账号记录，普通runtime不得伪造CLEAN。专用连接固定同一应用数据库，不以owner执行业务。

UploadState: `CREATED` → `UPLOADED` → `PUBLISHED`；扫描暂时失败为`SCAN_FAILED`（可再次complete）；感染为终态`REJECTED`，不能换键反转。扫描是同步有界操作，UI可显示“扫描中”；数据库不靠永久SCANNING锁定崩溃会话。

已发布资产在事务提交时必须满足：修订号从1连续唯一；`next_revision_no = MAX(revision_no)+1`；current指向最大revision。服务可以在同一事务内分配编号、插入修订、推进current，但不能提交孤立allocator advance、倒退或跳号。第一版不支持直接将current切回旧版本；未来业务回滚须另行设计。旧修订和冻结附件不受新版本影响。

所有下列UUID/时间为JSON string，时间ISO8601；nullable字段显式null；不输出内部对象key。

```ts
type Upload = {
  id: string; projectId: string; assetId: string | null; revisionId: string | null;
  filename: string; declaredSize: number; declaredSha256: string; declaredContentType: string;
  actualSize: number | null; computedSha256: string | null; detectedMime: string | null;
  state: 'CREATED'|'UPLOADED'|'SCAN_FAILED'|'PUBLISHED'|'REJECTED';
  cleanupPending: boolean; lastErrorCode: string | null;
  createdBy: string; createdAt: string; rowVersion: number;
};
type Receipt = { uploadId: string; state: 'UPLOADED'; size: number; sha256: string; mime: string };
type AssetRevision = {
  id: string; projectId: string; assetId: string; revisionNumber: number;
  filename: string; size: number; sha256: string; mime: string; scanStatus: 'CLEAN';
  createdBy: string; createdAt: string;
};
type Asset = {
  id: string; projectId: string; currentRevisionId: string; currentRevision: AssetRevision;
  createdBy: string; createdAt: string;
};
type Attachment = {
  id: string; projectId: string; kind: 'REQUIREMENT'|'TEST_CASE'|'DEFECT';
  requirementId: string | null; testCaseId: string | null; defectId: string | null;
  assetId: string; assetRevisionId: string; revision: AssetRevision;
  createdBy: string; createdAt: string;
};
type Page<T> = { items: T[]; nextCursor: string | null };
```

## HTTP

共同前缀`/api/v1/projects/{projectId}`。JSON端点拒绝未知字段、错误类型、客户端actor/路径/scanner结果。写必须同源CSRF；POST还必须`Idempotency-Key`。分页`limit`默认25、最大100，`cursor`为不透明游标；不得一次自动下载所有页。

| 方法/路径 | 请求 | 成功 |
|---|---|---|
| POST `/uploads` | `{filename,size,sha256,contentType,assetId?}` | 201 Upload；assetId省略为新资产，提供为追加版本 |
| GET `/uploads` | cursor/limit，当前主体会话 | 200 Page<Upload>，用于结果未知后的明确恢复 |
| GET `/uploads/{id}` | 无 | 200 Upload |
| PUT `/uploads/{id}/content` | raw body，Content-Type为声明类型 | 200 Receipt；同内容始终重放同一receipt |
| POST `/uploads/{id}/complete` | 严格`{}` | 200 Upload（PUBLISHED）；成功响应冻结重放 |
| GET `/assets` | cursor/limit | 200 Page<Asset> |
| GET `/assets/{assetId}` | 无 | 200 Asset |
| GET `/assets/{assetId}/revisions` | cursor/limit | 200 Page<AssetRevision> |
| GET `/assets/{assetId}/revisions/{revisionId}` | 无 | 200 AssetRevision |
| GET `/assets/{assetId}/revisions/{revisionId}/download` | 无 | 200 原始bytes，后端代理且重新授权 |
| POST `/attachments` | `{kind,requirementId?或testCaseId?或defectId?,assetId,assetRevisionId}` | 201 Attachment；恰好对应kind的一个目标字段 |
| GET `/attachments` | `kind`及对应typed目标ID，cursor/limit | 200 Page<Attachment> |

附件冻结assetRevisionId；无unlink/delete，不随current revision漂移。三个目标均有真实同tenant/project复合FK；soft-deleted需求禁止新link，历史保留。重复相同目标/修订关联返回既有link，不制造事件；同键不同内容仍409。

下载header：`Content-Type`为服务端检测MIME，`Content-Length`真实长度，`Content-Disposition: attachment`安全编码文件名，`X-Content-Type-Options: nosniff`；不提供inline预览、公共URL或存储路径。鉴权与revision存在检查完成前不读存储内容。

## 错误与恢复

沿用现有扁平`{code,message}`与HTTP status（不另造嵌套error结构）。未认证401/UNAUTHENTICATED、角色拒绝403/FORBIDDEN、不可见或错误归属404/NOT_FOUND；CSRF错误保留现有错误模型，不当作全局登出。

- 400/INVALID_REQUEST：严格JSON、字段/键/非法大小或hash格式、类型。
- 413/FILE_TOO_LARGE：实际超过25MiB。
- 422/CONTENT_MISMATCH：实际size/hash或声明MIME与检测不符；不确认receipt或发布。
- 415/UNSUPPORTED_FILE_TYPE：实际禁止/未知类型。
- 409/UPLOAD_CONTENT_CONFLICT：已确认uploadId收到不同内容；绝不覆盖旧receipt/object。
- 409/UPLOAD_NOT_READY：CREATED不能complete。
- 422/FILE_INFECTED：本次真实扫描感染，持久化REJECTED。FIX01冻结该complete命令的HTTP status、code和safe message；有效期内，同主体/项目/route/key/hash重放先重新鉴权，仍返回同一422/FILE_INFECTED，且不重扫、不新增scan/audit/Outbox、不改变upload版本。此前R11实现第二次同key返回409是已记录缺陷，不是本条契约。
- 409/UPLOAD_REJECTED：新的complete key作用于已REJECTED会话；不同于原key的冻结重放。已过期键不承诺原响应，按新意图及当前终态拒绝，不能反转感染结果。
- 503/SCAN_UNAVAILABLE：scanner ERROR/timeout/unavailable，持久化SCAN_FAILED，不发布，可同意图重试。
- 503/STORAGE_UNAVAILABLE：对象存储失败，不能伪造DB成功。
- 409/IDEMPOTENCY_KEY_REUSED：同route/actor/project/key不同内容。

已记录的complete key先比对摘要；即使新body违反严格`{}`，也不能把既有命令的摘要冲突混成新意图。新key仍按严格字段校验400。暂时扫描失败503不持久化为永久命令结果，原key在scanner恢复后可明确重试；PUBLISHED成功快照保持原JSON格式，兼容其他模块成功重放。冻结业务错误仅保存受控status/code/message/outcome标识，不保存内部异常或堆栈；撤权、停用、租户有效期失效均先拒绝，不返回旧结果。

## Scanner身份配置职责

Flyway只管理`test365alm_scanner`能力角色与表权限，不管理登录秘密。已有V19/V20升级时使用仓库正式`tools/provision_asset_scanner_role.py`创建独立LOGIN子角色，只继承scanner能力，不加入runtime、不拥有业务对象、无SUPERUSER/CREATEDB/CREATEROLE/BYPASSRLS。已有fresh初始化的group LOGIN不被改密码或停用。同名安全角色可以幂等核验并用所给秘密验证登录，不旋转密码或扩大权限；不安全角色明确拒绝，操作员核验现有身份，凭据轮换另行处理。

应用继续显式读取`TEST365ALM_ASSETS_SCANNER_DB_USERNAME`与`TEST365ALM_ASSETS_SCANNER_DB_PASSWORD`；不能回退runtime/migrator密码，连接URL固定同应用数据库。工具只供已授权owner/operator，秘密安全输入、不打印、不提交；本轮真实provision只在测试专属PostgreSQL。生产凭据保管、轮换及网络隔离仍须运维评审。

外部I/O与DB没有2PC：会话创建即分配内部唯一quarantine及候选published对象身份。receipt事务失败保留CREATED，外部对象可能存在但不能下载，同uploadId安全重试；发布对象成功而DB事务失败时可识别为会话候选orphan，因无DBrevision无业务下载；publish事务成功而quarantine删除失败，PUBLISHED保持，cleanupPending=true可诊断。清理不改变冻结发布响应；后续状态GET显示当前cleanup状态。本轮不做GC daemon。

并发采用DB锁和持久化幂等串行化相同命令；同uploadId相同PUT只一receipt，相同complete只一有效发布/scan结果。已有asset版本分配锁定asset而非裸MAX()+1；两个并发新版本唯一递增。合法CLEAN发布/关联与audit/Outbox/幂等同事务，分别注入audit/outbox失败；恢复后同意图安全继续。

## 页面与证据

扫描证明是独立持久化事实；发布业务事务回滚时，已完成的真实扫描记录可以保留，但不是已发布版本且不提供下载。不能将该记录误算为业务发布成功或声称外部扫描随业务回滚。

详情附件面板包含：选择文件→创建会话→raw上传→扫描发布→冻结关联→下载。新版本单独明确意图；旧link仍rev1。UI区分未上传/待扫描/扫描失败/拒绝/发布/未知，未知写保留原键及内容，只GET恢复确认状态，不自动另建会话；切项目/失权清空敏感范围并使迟到响应无效。可从本人会话列表恢复服务器已接收但UI未知的upload。

真实服务器验证必须包括PostgreSQL + private S3-compatible + clamd及EICAR；浏览器真实Keycloak普通MEMBER/VIEWER，单worker零重试。各精确报告门禁拒绝缺失/失败/错误/跳过/重复。完整限制、非空升级、对象/DB故障、版本/授权场景对应N01—N10，不提升原M05完整规划验收状态。
