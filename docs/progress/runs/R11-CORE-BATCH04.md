# R11-CORE-BATCH04 执行记录

全文：[R11输入](../inputs/R11-CORE-BATCH04.md)。最小切片已实现且固定代码Push/PR六Job通过，等待独立审核；内部自审不等于外部审核。最终文档提交的SHA/CI由交付回复核对，本文冻结已有代码证据而不循环追写自身SHA。

## 起点与边界

2026-10-17核对GitHub：默认`chore/r01-status-001@a2419e292c004b2cd4c7278bdaa40b1ce3442a22`，PR9仍Open/unmerged，head`d213e47bebbe0f89522be48c3e58003d008cdf06`与审核相同。未发现R11分支或后续用户提交；本地该head工作区干净。普通fetch失败`getaddrinfo() thread failed to start`，授权重试相同fetch退出0。新建`feat/r11-m05-001`，计划新PR base `feat/r10-m06-001`、Depends on PR #9；未合并PR9。

R10最终Push38937842395/PR38937893784六Job成功，PR实际checkout`1c82824b11111d11c9491e2540036f5fddf2b923`；46单元/125集成/181前端/95Python/13完整浏览器+独立1，作为保留基准。独立审核反馈的相同内容PATCH仍递增版本为后续公共API精修，不扩入本轮。

## 实施顺序和验证清单

原`tasks/plan.md`/P0任务不覆盖；本用户指定轮次记录维护本轮细化任务。

- [x] 真实GitHub/本地基准，完整原文归档，建立单层依赖分支。
- [x] 冻结[资产接口契约](../../../contracts/assets.md)及威胁边界：不可信文件/文件名/MIME、scope、S3/DB非2PC、scanner fail-closed。
- [x] 受控隔离上传纵向切片：adapter/真实容器、规则RED→GREEN、session/raw PUT、无发布前下载。
- [x] CLEAN发布/不可变版本/typed link/download及真实DB、HTTP、并发/故障/升级验证。
- [x] 实际详情附件UI、确定性状态回归及真实MEMBER/VIEWER DOM流程。
- [x] 精确门禁、六Job、完整回归、自审修复、代码提交CI。
- [ ] 文档交付、最终Push/PR CI、实际checkout/远端文件回读、独立审核停止点。

本轮不部署、不恢复定时任务、不改变默认分支/保护/可见性，不进行生产资源或客户数据操作；不开发M12/后续模块。N01—N09代码证据PASS；N10固定代码六Job PASS，最终文档交付Push/PR仍须在交付回复独立核对。以下实施过程中的NOT_RUN/失败是各阶段历史，不能覆盖最终冻结证据，也不能被成功记录静默删除。

## 实施过程实际命令与自修记录（历史）

本机2026-10-17：Java17.0.2、Node26.0.0、Python3.12.8；目标CI Java21/Node24，编译target17未改变。Docker client29.7.2存在，但`docker version`访问`dockerDesktopLinuxEngine`管道不存在，本机真实PG/S3/clamd/Keycloak执行BLOCKED_LOCAL，不据此跳过CI。

| 命令/位置 | 退出码与实际结果 |
|---|---|
| 根目录 `python -m unittest tools.tests.test_r11_asset_evidence -v` | RED 1，新门禁模块尚未实现；不是业务验收 |
| 根目录 `python -m unittest tools.tests.test_r11_service_probe -v` | RED 1（缺模块），实现后0/3项；只验证离线探针逻辑 |
| 根目录 `python -m unittest discover -s tools/tests -v` | 沙箱临时目录权限失败1/104项；正常权限重跑1，仅旧浏览器正向fixture漏新M05名称，修正实际fixture后`-q`退出0/104项 |
| 根目录 `python tools/verify_r11_asset_web_report.py local-evidence/r11-frontend.json` | 0，真实Vitest210项，M05组件24+API5确切名称，无failure/error/skip/duplicate |
| 根目录 `python tools/validate_package.py` / `python tools/p0_health_check.py` | 均0；仍184产品AC未通过、P0 B01—B05 INPUTS_MISSING，不是业务验收 |
| apps/web `npm run test:run`、`npm run build`、`npm run lint` | 分别0/210项、0、0；原9项lint warning保留，无新warning |
| apps/server 系统`mvn -B -ntp test-compile` / `mvn -B -ntp test` | 分别0；完整单元60项，failure/error/skip均0；HTTP/容器IT仅可编译，不等于已执行 |
| apps/server Windows `.\mvnw.cmd -B -ntp test` | 1，用户缓存`io/minio`目录AccessDenied；未执行测试，不与系统Maven成功混记 |
| apps/web `npm ci` | 0，127 packages；随后Vitest沙箱临时缓存rename EPERM退出1/零测试，正常权限重跑0/210项；lint0、build0 |
| apps/web `npm audit --omit=dev --registry=https://registry.npmjs.org --json` | 0，production报告0漏洞；不是全量安全认证 |

前端已捕获并修复真实RED→GREEN：发布成功后读取失败无法再次刷新；并发资产rev2发布时读取本会话应精确使用已确认revisionId，不误选currentRevision。失权、项目切换、未知意图、延迟回包、取消/超时/StrictMode和blob URL清理保留回归。Playwright仅发现14个用例，实际执行尚待CI。

适配器初次Maven缓存写入AccessDenied与测试socket权限失败独立记录；不算业务RED。Windows安全软件阻止EICAR测试落盘，不绕过；本机检测单元用内存，Ubuntu真实EICAR上传/扫描IT保留。正常权限完整单元曾58项/1error：MinIO9复制前HEAD需要真实Content-Length/Last-Modified，已修正fixture并精确断言请求顺序，未改变生产复制实现。新增真实Tomcat上传读空闲测试2项后，全套60项通过；不删除断言。

安全自审发现普通runtime原可INSERT CLEAN扫描结果，已改为独立受限scanner DB identity，测试要求runtime直接伪造42501。真实扫描日志独立提交，业务发布回滚允许保留扫描事实但无可下载revision；已感染证明即便audit回滚也不能通过重扫反转。不是S3/DB 2PC。raw PUT新增15s可配置读空闲限制并用真实不完整HTTP流验证；它不是总墙钟预算。

本轮新增/修改文件按职责：`asset/`生产/测试、正式V20、2个OIDC HTTP矩阵、旧升级到latest计数与冻结V18→V19目标；附件面板/API/三处详情接线及新browser；私有Compose、配置生成/受限账号、V21失败探针、精确M05门禁/离线对抗测试、原6Job证据白名单；契约/ADR025/README/AGENTS/本输入与记录。发布前核对完整git清单，原V1—V19无改动。

## 首次真实CI与补强（历史保留）

首次代码候选`1fcbda1caae9a427470fd1156e53fcbf9b422cbf`经普通git正常推送，创建[PR10](https://github.com/fangzhiy/Test365Alm/pull/10)，base为`feat/r10-m06-001`、Depends on PR9。普通push同样遇到沙箱DNS错误，授权相同命令退出0；远端ref回读与本地相同，无Git Data API映射差异。

- [Push38945148129](https://github.com/fangzhiy/Test365Alm/actions/runs/38945148129)及[PR38945221859](https://github.com/fangzhiy/Test365Alm/actions/runs/38945221859)均failure：仅server失败，其余五Job成功。
- Push server117122280298：Surefire60成功；Failsafe143、failure2/error12/skip0。首个故障为`scannerUnavailableAndTimeoutRemainRetryableWithoutPublication`在真实扫描器恢复阶段超时180秒，随后共享scanner不可用；两个新HTTP方法503。非空V19升级先执行成功，其余旧模块保持通过。修复中，不据其他Job绿色判整轮通过。
- 两条browser完整14项及独立项目UI1项通过；真实S3匿名403、真实clamd benign CLEAN、MEMBER上传/发布/reload/download/rev2冻结rev1、独立VIEWER负向均实际执行，不是mock扫描。后续代码仍须最终CI回归。
- 本地独立自审补强：同一runtime连接在revoke/disabled/tenant expiry后直接读六表为0；V20升级后六新表为空；同键并发audit/Outbox各6、core幂等3。属于补强断言，不新增业务范围。
- Seaweed官方日志会输出AccessKey及identity前8字符：Python日志过滤新增精确ACCESS_KEY及该前缀；Testcontainers启动失败日志也在输出前脱敏，不输出add-mask原值。合成回归实际RED→GREEN；Python105通过，后端新增1项后系统Maven61通过。只保留故障摘要，不隐藏失败。
- 首轮PR实际checkout为`f4d0403f18d6e558e8e2457925fdee5b4cea4bc4`，并非分支head。恢复失败的确切内部clamd重启原因没有足够日志，不能编造为已证实端口变化。有限修复改为冻结/恢复本轮真实scanner容器，fresh inspect校验ID/标签/StartedAt/真实端口始终相同；恢复后实际benign扫描须CLEAN，不重启应用。另保留真实连接拒绝及受控TCP读超时对照，均503且无发布；不是mock扫描返回。未提高180秒方法超时，恢复预算由180缩为60秒。下一CI实际验证此修复。

## 后续修复与环境核验

- 修复提交`b09a9b880c31b5d1780d11138564084a363420bf`：[Push38946476938](https://github.com/fangzhiy/Test365Alm/actions/runs/38946476938)与[PR38946499532](https://github.com/fangzhiy/Test365Alm/actions/runs/38946499532)的server均实际61单元、143集成通过，AssetDatabaseIT15、AssetMigrationUpgradeIT1、OIDC整个类23（新增M05仅2）；PR实际checkout/build为`18c9561ab304aa53a0511a665fa245675faa972a`。实际报告精确门禁通过，不把第一次失败抹去。
- 启动说明自审发现Windows的`import_r03_env.ps1`没有导入生成器写入的10个资产应用配置键；CI以受控`source`加载成功不能替代Windows启动链。新增`test_windows_import_explicitly_allows_generated_asset_application_keys`实际RED→GREEN，只显式添加规定的M05键，不允许任意SPRING/JVM/Compose覆盖。
- Windows修复提交`8f7fb3a437e9d17813cc8b4d5ae9040b632d8e5f`；PowerShell7.6.5独立子进程仅使用忽略的合成fixture，允许键10/10正确、未知键哨兵不变、源文件未改，退出0。首次诊断错误地以null判断空环境值退出1，改验证方法后通过，未为此改产品。未读取或修改用户已有`.env`。
- 根目录再次`python -m unittest discover -s tools/tests -v`：沙箱临时目录WinError5，106项/207环境错误，退出1；完全相同命令正常权限重跑106/106，退出0。不能把环境失败抹去或混为一次成功。本轮未改系统默认运行时/全局权限。
- 8f7候选[Push38946999225](https://github.com/fangzhiy/Test365Alm/actions/runs/38946999225)为failure：独立旧项目UI在首次admin登录后等待显示名超时，脱敏timeline显示`/api/v1/me` 401；同次完整14浏览器均通过，仍正确令Job失败，不能用完整套件成功覆盖独立对照失败。已下载并读取`r03-oidc-browser-evidence`(12067341848)的脱敏timeline/服务日志；其余五Job成功。[PR38947007145](https://github.com/fangzhiy/Test365Alm/actions/runs/38947007145)六Job成功、实际checkout`5dc0d63657f9ab98329c35e2c22494986bacb8f1`。未增加retry或关闭独立对照，首次失败因果继续核对。
- W24最终自审发现仅raw处理器不足以阻止Spring默认multipart提前解析。新增`AssetRawUploadConfigurationTest.actualApplicationConfigurationDoesNotRegisterMultipartParsing`读取真实应用配置，旧配置RED(1failure/exit1)，只加`spring.servlet.multipart.enabled=false`后GREEN(exit0)；真实OIDC M05矩阵补有效CSRF/合法upload的畸形multipart→422/CONTENT_MISMATCH和八表快照不变，无解析器bean。未开发新上传协议。原有IO异常finally另以唯一自有临时文件补证，完整系统Maven63/0F0E0S、test-compile0；真实新HTTP断言仍由随后CI执行。
- W24与临时文件证据提交`b82c601e788e6e1f6f424677527203fb919befcd`，没有改认证策略。8f7独立登录失败发生在业务夹具和viewer创建之前，清理未覆盖原始业务异常；后端该时间窗无principal-upsert成功记录，Keycloak无对应LOGIN_ERROR，只有`/me`401不足以证明具体认证/前端根因。候选请求缓存/首次会话竞态未经确定性证明，不据猜测修改认证或增加超时。
- `2a38148bfad5fd9b8e599b6d469a8e8b7601717d`仅补项目UI脱敏诊断：每步最多200条路径/状态/时间配对、两个OAuth路径及`loginFailed`布尔，不保存query/code/state/token/Cookie/响应体。新增3个合成脱敏用例RED→GREEN，完整前端213/213、build0、lint0（既有9warning），Playwright `--list`0/14项仅代表发现；真实运行仍由CI证明。原14浏览器和独立对照、单worker、零retry全部保留。

## 安全自审与范围限制

下列为实现/测试映射，不是渗透测试认证。真实执行身份见后续冻结CI表；单元mock不冒充外部服务验收。

| 输入W项 | 控制及证据 |
|---|---|
| 1、2、25 路径、CRLF、下载文件名 | AssetRules清理显示名；对象/临时路径仅服务端UUID；FileRulesTest、实际HTTP验证attachment/nosniff/安全编码 |
| 3—6 MIME/hash/截断/超限 | 实际Tika检测、不传文件名提示；FileRulesTest与`contentSizeHashMimeAndStreamBoundsRejectWithoutPublication`、真实HTTP矩阵；声明与流式25MiB双限 |
| 7—9 EICAR/timeout/unavailable | `realClamdCleanAndEicarProduceSafeTerminalStates`、`scannerUnavailableAndTimeoutRemainRetryableWithoutPublication`真实clamd；独立TCP拒绝/超时对照，无非CLEAN发布 |
| 10、11、20 隔离暴露/孤儿/公开URL | 真实私有S3匿名403；`publishedObjectThenDatabaseFailureIsInaccessibleAndRecoverable`；仅授权DB revision后代理下载，无presigned/public URL接口 |
| 12、13 跨项目UUID/同摘要跨租户 | typed复合FK、当前授权及runtime RLS；`typedLinksRejectForeignMissingAndDeletedTargets`、`boundedPagesAndSameHashTenantsRemainIndependent` |
| 14、15、16 冻结旧链/旧会话/VIEWER | `newVersionsPreserveFrozenAttachmentBytesAndImmutableRows`及两个真实OIDC矩阵；普通MEMBER和独立VIEWER DOM，不用tenant admin隐式授权 |
| 17、18 重复完成/并发版本 | `concurrentSameIntentCreatesOneReceiptScanAndRevision`、`concurrentNewVersionsAllocateUniqueRevisionNumbers`；锁内分配、事件/幂等数量精确断言 |
| 19 凭据日志 | AssetLogContainer在Testcontainers启动失败输出前遮罩实际合成访问键/秘密及服务公开的已知前缀；Python过滤器同规则；新增RED→GREEN，不隐藏一般异常 |
| 21 审计/Outbox内容 | AssetService只发送事件身份/修订/安全元数据，不包含bytes/对象凭据/socket；发布和关联两种audit/outbox失败分别回滚 |
| 22 临时文件 | FileRules仅请求自己创建的临时文件、异常finally删除；成功由Content.close删除；`interruptedReadDeletesOnlyTheOwnedSpoolBeforePropagatingFailure`唯一哨兵确认本请求新文件后验证IO异常清理，不读/删既有用户文件 |
| 23 浏览器对象URL | AttachmentsPanel受控blob下载，卸载/失权/替换时revoke；确定性组件覆盖清理，无token/内容进入timeline |
| 24 解析资源 | 显式关闭multipart自动解析，配置RED→GREEN及真实HTTP畸形multipart拒绝；实际流式上限、15s独立上传读空闲超时和真实不完整socket测试。没有声称完整DoS/吞吐压测已完成 |

独立scanner账号只写扫描证明，普通runtime无INSERT扫描结果/历史改写/DDL/BYPASSRLS；应用被完全接管或DBA不在安全承诺内。对象存储、扫描证明与业务事务没有2PC。S3单调用15秒、scanner默认30秒（IT15秒）、scanner DB10秒、业务事务180秒、HTTP读空闲默认15秒分别有界，不等于单一总墙钟上限。

完整M05的分片/大文件、去重、unlink/delete、GC/retention/legal hold、生命周期、CDN/公开签名URL、inline预览、OCR/转码、灾备/复制、旧ALM全量兼容仍未实现。SeaweedFS仅隔离测试，Filer TUS已知公告/生产服务选型与完整依赖安全审计仍需另行评审；不开放filer/master/volume端口。既有9条lint warning和Actions弃用提示未扩成依赖升级。本机真实容器验证BLOCKED_LOCAL由UbuntuCI实际验收补足，未使用owner执行业务。

进程崩溃后的临时文件GC、磁盘/并发配额、用户速率限制、完整资源耗尽压测、所有浏览器Unicode文件名兼容、生产病毒库更新运维均未验收；不把请求正常/异常清理或固定CI镜像扩大为这些能力。生产bucket/IAM安全策略需要独立评审，应用不会自动改写客户存储策略。

## N01—N10 对应真实场景

下面列出确切测试，不以整个Job绿色代替场景。DB方法均属于`com.test365alm.server.asset.AssetDatabaseIT`；升级类为`AssetMigrationUpgradeIT`；HTTP两个方法属于`OidcCallbackSecurityIT`，不是该类全部23项都算M05。完整冻结提交/运行/结果在本节之后列出。

| 条件 | 具体证据和范围 |
|---|---|
| N01 迁移/升级 | 空库正式V20；`v19NonEmptyDatabaseUpgradesToV20WithoutLosingExistingBusinessHistory`在真实非空V19逐表逐字段比较需求/用例/执行/缺陷/追踪/Release/Cycle/Snapshot/审计/幂等；六个新表初始为空，再由受限runtime发布/关联/下载 |
| N02 内容完整性 | `memberUploadUsesPrivateQuarantineAndServerVerifiedReceipt`及`contentSizeHashMimeAndStreamBoundsRejectWithoutPublication`；实际HTTP矩阵验证size/hash/伪MIME/25MiB+1/危险文件名，拒绝无发布副作用 |
| N03 隔离/扫描 | `realClamdCleanAndEicarProduceSafeTerminalStates`、`scannerUnavailableAndTimeoutRemainRetryableWithoutPublication`、`runtimeCannotForgeRevisionFromUnscannedOrRejectedUpload`；真实S3/clamd，CLEAN可发、EICAR REJECTED，pause/拒绝连接/TCP timeout均不发布，恢复同意图成功 |
| N04 冻结版本 | `newVersionsPreserveFrozenAttachmentBytesAndImmutableRows`；rev2推进当前版本，旧link仍rev1且字节相同，runtime UPDATE/DELETE/TRUNCATE拒绝；HTTP/DOM重复验证 |
| N05 typed关联 | `typedLinksRejectForeignMissingAndDeletedTargets`、`runtimeCannotForgeRevisionFromUnscannedOrRejectedUpload`；三类合法关联、错误ID/跨项目/跨租户/foreign revision/软删除无非法副作用；实际HTTP矩阵 |
| N06 当前权限 | `runtimeRlsAndCurrentMembershipProtectEveryScope`同一runtime连接六表在撤权/停用/租户过期后为0；`realOidcAssetHttpTypedLinksFrozenVersionsAndLiveDownloadRevocation`原会话直接metadata/download拒绝，不先调用/me；VIEWER读/写拒绝、非成员tenant admin不自动访问 |
| N07 幂等/并发 | `concurrentSameIntentCreatesOneReceiptScanAndRevision`、`concurrentNewVersionsAllocateUniqueRevisionNumbers`；并发PUT/complete唯一receipt/revision/scan/link，audit6/Outbox6/core幂等3；两个新版本编号唯一1/2/3，同键异内容409，HTTP冻结重放 |
| N08 外部失败/回滚 | `quarantinePutThenReceiptFailureHasNoDownloadAndSafelyRetries`、`publishedObjectThenDatabaseFailureIsInaccessibleAndRecoverable`、`attachmentAuditAndOutboxFailuresRollbackAndRecover`、`postCommitCleanupFailureRetainsPublishedAccessAndPendingState`；发布/关联audit和Outbox分别故障，DB整体回滚而S3孤儿仍存在但不可下载，同意图恢复；提交后清理失败仅cleanupPending |
| N09 真实页面 | `real Keycloak UI member uploads scans attaches and preserves old asset revision`；普通MEMBER、真实Keycloak/真实S3/真实clamd，DOM上传→扫描→冻结关联→reload→下载→rev2而旧link仍rev1；独立VIEWER DOM可读/下载、无写入口、有效CSRF直接写403 |
| N10 CI/证据 | 保留六Job/全部旧精确门禁；M05 DB15/升级1/HTTP2/前端24+5/浏览器新1逐名验证，离线对抗证明missing/failure/error/skip/duplicate拒绝；最终交付Push与PR实际结果另行核对 |

M05 HTTP第一个矩阵`realOidcAssetHttpIntegrityCsrfRolesAndIdempotency`覆盖严格JSON、CSRF、roles、上传声明与实际内容、重放及恶意multipart；第二个矩阵覆盖typed三目标、冻结版本与旧会话失权。业务连接均为受限runtime；owner只迁移/隔离夹具/故障注入/独立核验，真实业务不可由owner绕过RLS。扫描证明连接是独立受限身份，不是owner。

## 固定代码验证与制品复核

本地和远端代码均为 `2a38148bfad5fd9b8e599b6d469a8e8b7601717d`，无Git Data API转换；普通Git父链保留R10起点和本轮各分组提交。正式迁移只新增V20，旧V1—V19没有改动或删除。PR10仍Open，base `feat/r10-m06-001@d213e47bebbe0f89522be48c3e58003d008cdf06`，Depends on PR9；PR9未合并、默认分支未移动。

| 代码运行 | 实际检出/构建提交 | 六个Job |
|---|---|---|
| [Push38948375346](https://github.com/fangzhiy/Test365Alm/actions/runs/38948375346) | `2a38148bfad5fd9b8e599b6d469a8e8b7601717d` | planning-tools / web / server / readiness-recovery / migration-failure / oidc-browser 全部success |
| [PR38948383146](https://github.com/fangzhiy/Test365Alm/actions/runs/38948383146) | `f32dd2beb4bbf992f09c38ef4aeb6b687b0dbfb4`，refs/pull/10/merge | 同上全部success |

PR合并测试提交父节点为R10 base和代码head；树为 `1897bb51a6488475eebde4887d86abdaa1ece2b1`，与代码head一致，不能把merge-ref SHA写成head。真实报告与运行不是历史R10成功的替代引用。前一补强代码b82的[Push38948012618](https://github.com/fangzhiy/Test365Alm/actions/runs/38948012618)/[PR38948019546](https://github.com/fangzhiy/Test365Alm/actions/runs/38948019546)也六Job成功，PR checkout `047588fa53464ae9bca84ef894b91a71b42cc279`，真实畸形multipart拒绝已执行。

| 层/实际命令 | 数量/结果（两个代码CI） | 证据 |
|---|---|---|
| `./mvnw -B -ntp test` | 63，失败/错误/跳过0；较R10新增17 | backend-evidence/target/surefire-reports |
| `./mvnw -B -ntp -Pintegration verify "-Dbuild.commit=${GITHUB_SHA}"` | 143，失败/错误/跳过0；较R10新增18 | backend-evidence/target/failsafe-reports、integration-build.txt |
| M05真实PG/S3/clamd专项 | AssetDatabaseIT 15；非空V19→V20 1；真实OIDC HTTP 2。整个OIDC类23不是M05 23项 | asset-r11-report.txt，逐名门禁及XML |
| `npm ci` / lint / JSON test:run / build | 全退出0；Vitest213，失败/错误/跳过0。M05组件24+API5，另安全诊断3；原9项lint warning | frontend-evidence的JSON/安装/lint/build及精确M05/M06摘要 |
| `python -m unittest discover -s tools/tests -v` / validate_package / p0_health_check | 106项/退出0；规划与P0命令0但原184产品AC未通过、B01—B05输入缺失不变 | planning-evidence，stderr与stdout均保存 |
| `npm run test:e2e`、独立项目UI命令 | 完整14/14（M05仅1）+独立项目UI1/1；两次退出均0，单worker/零retry，零失败/错误/跳过 | browser-status.txt / playwright-results.xml / asset-ui-context-full.json |
| 故障/资源归属检查 | 数据库停止503/live200/同进程恢复200、预存环境不变；正式V20成功后专用V21迁移失败，均退出0 | readiness-recovery-evidence / migration-failure-evidence |

CI实际Java21.0.12.1、Node24.21.0、Python3.12.15、Wrapper Maven3.9.16、Ubuntu/Linux；本机Java17.0.2、Node26.0.0、Python3.12.8、系统Maven3.9.14、PowerShell7.6.5。编译target17未改变。WindowsWrapper本轮AccessDenied退出1/零测试，不能把CI Wrapper或系统Maven成功写成本机Wrapper成功。

下载代码Push原始制品（仅脱敏白名单，不提交仓库）再次核验：

- backend artifact12067533674 → `local-evidence/r11-code-backend/`；PowerShell读取全部XML实际63/143且F/E/S均0；`python tools/verify_r11_asset_report.py local-evidence/r11-code-backend/target/failsafe-reports`退出0，15+1+2确切名称存在。
- frontend artifact12067502813 → `local-evidence/r11-code-frontend/`；M05与M06 JSON精确门禁均退出0，213条断言实际执行。
- browser artifact12066129798 → `local-evidence/r11-code-browser/`；`verify_r03_report.py`与`verify_r03_project_browser_report.py`对下载XML均退出0，14/0/0/0；browser-status两个退出0；asset-services证实S3匿名拒绝及真实clamd benign CLEAN。
- 六种制品均存在且未过期；PR server117133212280与browser117133212337日志独立确认计数/精确门禁/merge-ref。失败摘要步骤按条件跳过不是测试跳过，浏览器构建jar的skipTests也不取代server已执行的单元/集成。

上述固定代码下N01、N02、N03、N04、N05、N06、N07、N08、N09均PASS，场景对应前表。N10代码Push/PR通过；包含本记录的最终交付SHA与最终CI须在回复核对，不预先宣称尚未发生的成功。没有改写完整M05或原规划验收状态。

安全发布检查：`git diff --check`退出0；全部新增/修改文件逐项检查、无删除，V1—V19无diff。tracked秘密模式检查未发现私钥、GitHub令牌或AWS访问键；仅提交配置变量名/合成测试夹具，实际.env/realm/扫描凭据均被忽略。完整原输入已按代码SHA从远端回读，Git blob `eeedfa7352aad19d29a4db9d305fec128f89ee20`与本地归档一致；最终交付再回读输入、ADR、契约、记录及状态，避免仅本地存在。

8f7历史孤立登录间歇故障仍是未确定根因的剩余风险；当前固定代码Push/PR和前一b82均完整通过，不意味着其因果已根治。保留原对照、零retry和新增安全诊断，未通过重写认证、忽略失败或无限重跑制造通过。当前最小切片无已知未满足必需条件；本机容器/Wrapper限制及生产运维未验收范围独立保留。

## 复现与演示

1. 按README创建全新本机/CI-owned隔离环境，不覆盖既有`.env.r03`。固定SeaweedFS 4.48 digest `4e61d15fd35994cb1e43e1e553dff106794841fd9a99ade2fc8c8bfce4d7872d`，ClamAV 1.5.4-debian13-slim digest `9a96980b265cb2b51a20bdcca921dc80aa55ed0e56cdfb076198ee27a9171927`；详细依赖/许可证/官方来源在ADR025。
2. 用真实Keycloak普通PROJECT_MEMBER登录，选择有读取/上传权限的项目，在需求/用例/缺陷详情“附件”选择一个普通`.txt`文件，完成创建会话→上传→扫描→关联。只能CLEAN发布；不显示桶路径/存储凭据。
3. 重载页面重选相同范围，下载已冻结版本；选择原资产上传不同内容新版本，current变rev2，原附件仍显示/下载rev1。失败/未知结果点击原意图重试或GET恢复，不自动换键重建。
4. 独立VIEWER登录可读/下载，无上传/创建版本/关联按钮；直接HTTP带合法CSRF写仍403。管理员撤销其membership后，原会话下一次metadata/download失权，页面清理旧范围。
5. EICAR和扫描器暂停/超时、audit/Outbox回滚仅通过专属集成资源测试；不要在用户开发库或日常扫描服务故障注入。停止本机服务使用同Compose文件/项目名`stop`，不删卷。

本机验证命令：根目录`python -m unittest discover -s tools/tests -v`、`python tools/validate_package.py`、`python tools/p0_health_check.py`；apps/web执行`npm ci`、`npm run lint`、`npm run test:run -- --reporter=verbose --reporter=json --outputFile.json=../../local-evidence/r10/vitest.json`、`npm run build`。apps/server系统Maven为`mvn -B -ntp test`，Windows Wrapper独立为`.\mvnw.cmd -B -ntp test`，结果不能混记。Linux CI完整为`./mvnw -B -ntp -Pintegration verify "-Dbuild.commit=${GITHUB_SHA}"`，之后根目录`python tools/verify_r11_asset_report.py apps/server/target/failsafe-reports`。本机Docker不可达时不要伪造后两项成功。

证据制品：`backend-evidence`含Surefire/Failsafe XML、`asset-r11-report.txt`及所有旧gate摘要；`frontend-evidence`含完整Vitest JSON及M05精确摘要；`r03-oidc-browser-evidence`含`playwright-results.xml`、`asset-ui-context-full.json`、真实S3/clamd探针、独立项目UI结果和脱敏日志；另有planning/readiness/migration三个制品。报告缺失不视为通过，未上传原始OAuth trace或秘密配置。
