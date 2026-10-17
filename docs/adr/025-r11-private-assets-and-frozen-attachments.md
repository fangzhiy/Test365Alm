# ADR 025 — M05 私有资产、可信扫描与冻结附件

状态：R11 实施决策；实际验收见[轮次记录](../progress/runs/R11-CORE-BATCH04.md)，不代表生产上线或完整 M05。

## 选择和边界

沿用 Spring Boot/PostgreSQL/React/OIDC/RLS。正式新增 V20，不修改 V1—V19；专用失败探针由工具临时生成 V21。稳定 asset 与追加 asset_revision 分离，attachment_link 对 Requirement/TestCase/Defect 使用恰好一个 typed FK 与同租户/项目复合归属。附件冻结具体修订；新版本不会改变旧下载。

BlobStore 的生产实现为真实 S3-compatible SDK，不使用内存对象冒充存储。本机/CI 对象服务仅为可丢弃测试资源：MinIO 镜像匿名入口实际无法取得，因此选择任务允许的官方维护等效 S3 服务 SeaweedFS，协议仍由 MinIO Java SDK 调用。私有 quarantine/published 两个 bucket，不开放 filer/master/volume/admin，关闭 telemetry，不生成公开或长期预签名 URL。所有宿主端口仅 127.0.0.1。

| 项目 | 锁定版本 |
|---|---|
| Java S3 SDK | io.minio:minio 9.0.3 |
| JVM HTTP implementation | com.squareup.okhttp3:okhttp-jvm 5.3.2；Maven 多平台依赖必须显式选 JVM artifact |
| 内容检测 | org.apache.tika:tika-core 3.3.3；只检测，不引入文档 parser |
| 隔离 S3 镜像 | chrislusf/seaweedfs:4.48@sha256:4e61d15fd35994cb1e43e1e553dff106794841fd9a99ade2fc8c8bfce4d7872d |
| 隔离扫描镜像 | clamav/clamav:1.5.4-debian13-slim@sha256:9a96980b265cb2b51a20bdcca921dc80aa55ed0e56cdfb076198ee27a9171927 |

这些镜像 tag/digest 已从 registry 实际核对；可运行性由本轮真实 CI 另行证明。既有 Java17 编译目标、本机 Java17/Node26、CI Java21/Node24 不混称同一环境；不升级既有技术栈。

## 不可信文件与扫描

1字节..25MiB 声明与实际流式限制；服务端重新计算 SHA-256，检测内容类型只允许 text/plain、PDF、PNG、JPEG。文件名仅显示，去除路径/CRLF/控制字符，服务端 UUID 生成对象位置。raw PUT 不走 multipart，并显式设置 `spring.servlet.multipart.enabled=false`，防止进入处理器前被默认解析器写盘；真实配置测试RED→GREEN和实际OIDC HTTP畸形multipart拒绝验证这一边界。请求独有临时文件在 finally 删除；进程崩溃清理和全量资源耗尽防护不在本轮承诺内。

clamd 使用有界 INSTREAM，不发送本机文件路径。只有精确 CLEAN 才发布，INFECTED 持久化终态 REJECTED；异常/未知/超时为 SCAN_FAILED，允许原意图明确重试但不能下载。clamd TCP 本身不提供认证/TLS，因此只用于可信隔离本机/容器网络，不作为公网服务。

扫描结果写入进一步分权：普通 test365alm_runtime 无 INSERT scan_result 权限；独立受限 scanner 数据库身份只记录经过真实扫描的证明。扫描连接固定同一应用数据库，不接受另一任意 JDBC URL。该凭据不可下发客户端/打印，默认未配置时 fail-closed。这不是抵抗被完全接管的整个应用进程或 DBA；也不把自定义 GUC 当不可伪造扫描证明。

## 非 2PC 与冻结结果

S3、扫描证明与业务事务没有分布式原子提交：

- 会话预先持久化随机候选对象身份。PUT 成功而 receipt 事务失败时，对象仍是不可下载 quarantine；同 uploadId/声明内容重试覆盖同一内部位置。
- 真实扫描证明可先独立持久化；随后发布业务事务失败时，它是可诊断的孤立证明，不是已发布资产。不得因回滚宣称扫描/对象也自动撤销。
- published copy 成功但 DB 发布回滚：候选 orphan 没有已授权 revision，业务下载不存在。恢复后相同意图安全重试。
- 发布 DB 成功但 quarantine 删除失败：已发布版本有效，cleanupPending 保留；冻结幂等响应不因后台清理改变。
- 资产/修订/关联、对应 audit/Outbox/命令幂等在业务事务内一起成功或回滚。旧修订、扫描记录和关联不能更新/删除/TRUNCATE。
- 同内容 PUT 冻结 receipt，完整发布/关联持久化幂等；版本分配在数据库锁内进行，不裸 MAX()+1。

本轮不做 GC、保留策略、分片、去重、预览、跨区域、全量兼容。所有下载走后端重新鉴权，未入项目租户管理员没有隐式权限；每次请求重新检查主体/租户成员/项目成员。下载强制 attachment、nosniff、真实长度和检测 MIME。

## 原始来源与限制

### FIX01：提交完整性、冻结错误与身份provision

原V20允许runtime单独修改allocator，current guard依赖同一可变allocator，不能单靠服务调用顺序证明单调性。FIX01用新增V21的延迟约束检查最终数据库状态：修订正数且唯一的既有约束配合count=max、allocator=max+1、current=max；资产变化和修订插入都触发，非法最终状态在提交时拒绝。保留正常事务中短暂分配窗口及旧版本内容，不提供current历史回退功能；不改V1—V20。正式V21之后，独立启动失败探针改为V22。

INFECTED terminal complete与其业务审计/Outbox在同事务保存冻结错误outcome；沿用core幂等作用域和有效期，新增显式版本化错误信封但不改旧成功JSON。重放先重新授权并核对hash，再重放safe status/code/message，不通过当前REJECTED状态猜测旧结果。不同key仍409；transient503不冻结。该选择避免专属重复幂等表，但必须完整回归其他模块的成功重放。

数据库能力角色和LOGIN secret provisioning分离：Flyway不放密码；正式Python运维入口及唯一SQL源创建独立受限LOGIN子角色，只授scanner membership；既有同名安全身份幂等核验，不安全身份明确拒绝，不旋转秘密或扩大权限。测试升级必须调用正式来源并证明真实CLEAN写证明与发布，不以临时helper隐式授权冒充交付。保留fresh group LOGIN的既有兼容，但生产推荐独立LOGIN。数据库group不是任意runtime GUC授权；应用scanner仍用显式secret且固定同一URL。

本段为决策说明，RED复现、GREEN结果、非空V20数据和具体运行身份见[FIX01记录](../progress/runs/R11-CORE-BATCH04-FIX01.md)。PostgreSQL依据：[延迟约束触发器](https://www.postgresql.org/docs/17/sql-createtrigger.html)、[角色属性](https://www.postgresql.org/docs/17/role-attributes.html)、[SCRAM口令认证](https://www.postgresql.org/docs/17/auth-password.html)。不把环境变量秘密当不可被同机特权进程读取的安全存储，也不承诺生产全量安全审计。

FIX01自修进一步明确：libpq的`PGSERVICE=`不是unset，容器入口用`env -u`移除偏转项。正常连接并不足以证明密码，因为trust可以不检查密码；正式入口两次连接以显式`require_auth=scram-sha-256`和固定`PGREQUIREAUTH`要求完整握手，不支持该参数也安全拒绝。只对本轮测试容器初始化host SCRAM，不替用户改HBA或放宽权限。该决定由真实错误密码反例推动，保留原失败及同资源对照；依据[libpq认证要求](https://www.postgresql.org/docs/17/libpq-connect.html#LIBPQ-CONNECT-REQUIRE-AUTH)。

- [MinIO Java 9.0.3 README](https://github.com/minio/minio-java/tree/9.0.3)、[OkHttp JVM 依赖说明](https://github.com/square/okhttp/tree/parent-5.3.2)、[Tika core 文档](https://tika.apache.org/3.3.3/gettingstarted.html)。
- [ClamD INSTREAM 协议](https://docs.clamav.net/manual/Usage/ClamdProtocol.html)、[官方容器说明](https://docs.clamav.net/manual/Installing/Docker.html)、[SeaweedFS mini 源码](https://github.com/seaweedfs/seaweedfs/blob/4.48/weed/command/mini.go)。
- 新 Java 库为 Apache-2.0；SDK 不是 MinIO server。已核对上游公告，9.0.3 不在 MinIO SDK CVE-2025-59952 的 <8.6.0 范围；Tika core 不引入 PDF parser。不能将局部公告核对写成完整依赖安全认证。
- SeaweedFS 存在 [Filer TUS 公告](https://github.com/seaweedfs/seaweedfs/security/advisories/GHSA-99q7-x53r-6j4g)；本轮仅隔离测试、不开通该接口/身份功能，不对生产采用该镜像作安全背书。正式对象服务选型和持续漏洞治理仍需另行评审。
- ClamAV独立服务为GPL-2.0-or-later，未把其代码链接进应用；这不是分发法律审查结论。锁定镜像实际amd64配置无隐式VOLUME，Compose仍显式命名/标记扫描数据库卷以统一归属清理。
