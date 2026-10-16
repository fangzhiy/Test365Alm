# R11-CORE-BATCH04-FIX01 执行记录

完整输入：[原文](../inputs/R11-CORE-BATCH04-FIX01.md)。本轮仅修正资产allocator/current单调完整性、感染complete冻结幂等及正式scanner登录身份provision。以下为实际过程，未完成的验收不得写PASS；保留原R11执行记录及红CI。

## 实际起点

2026-10-17重新fetch退出0，并通过GitHub接口核对PR10仍Open/unmerged，head为 `c150958d964b85370393373516e377465ceb7ce2`，base `feat/r10-m06-001@d213e47bebbe0f89522be48c3e58003d008cdf06`。PR9仍Open，默认分支 `chore/r01-status-001@a2419e292c004b2cd4c7278bdaa40b1ce3442a22`。本地同SHA、工作区干净；继续现有PR10，不新建PR11。

审核基准Push38949795012/PR38949800659六Job成功，实际PR checkout `1b6993441c442890b0a5ee85a5d58c2fa7e5b6aa`；63单元/143集成（M05 DB15+升级1+HTTP2，整个OIDC23）/213Vitest/106Python/14完整浏览器+独立项目UI1，均零F/E/S。这是旧基线，不代表本FIX已通过。

## 顺序与安全边界

1. 先新增真实runtime/合法MEMBER的allocator回退、跳号、旧current及感染同key重放回归，原实现运行RED。
2. 仅追加V21约束，不改V1—V20；测试故障探针移V22。修复持久化terminal error，同key先重新授权，不冻结transient503。
3. 正式repository provision逻辑与真实非空V20升级共用同一来源；数据库能力role与LOGIN/secret配置分开。仅操作本轮Testcontainers资源，不连接实际开发或生产库。
4. 精确门禁/原六Job、所有旧回归保留；最终代码提交验证后再交付文档，不循环追写自身SHA。

本机 `docker version` 当前退出1：29.7.2客户端存在，desktop-linux engine命名管道不存在。本机真实PG/S3/clamd为BLOCKED_LOCAL；使用已有UbuntuCI运行真实红/绿证明，不用mock或owner执行业务替代。历史38946999225登录/me401根因未证明，不改认证/超时/retry。

## RED阶段及本机过程（历史，不代表最终状态）

仅新增回归的远端提交`c3c1d13bd471c5c677752bec9f4da5dc07956f27`已经实际push退出0，父提交为上述c150958；当时未修改任何生产文件。新增AssetDatabaseIT 1项（6类独立runtime攻击）及AssetTerminalOutcomeIT 5项。系统Maven `mvn -B -ntp -DskipTests test-compile`退出0仅证明编译，不作测试通过；真实[Push38953261084](https://github.com/fangzhiy/Test365Alm/actions/runs/38953261084)/[PR38953268643](https://github.com/fangzhiy/Test365Alm/actions/runs/38953268643)已触发。

Push server Job117149672709实际RED：63单元通过；149集成，3失败、0error、0skip，Failsafe退出1。`AssetDatabaseIT.publishedAssetAllocatorAndCurrentPointerCannotMoveBackwardOrGap`中allocator回退、任意+100、allocator+current一起退回rev1、无修订advance、真实CLEAN新修订跳号共5种均实际提交成功；单独pointer回退被旧guard正确拒绝。`AssetTerminalOutcomeIT`5项中2失败：同key/content首次422/FILE_INFECTED第二次409/UPLOAD_REJECTED；同key不同hash为400而非409。新key409、撤权/停用/过期拒绝、真实scanner暂时失败后原key恢复3项通过。失败发生在目标断言而非环境或权限夹具。确认真实RED后才开始V21和production terminal修复。

报告门禁自身新增精确名称断言：旧REQUIRED运行1项失败退出1；新增F01、5个terminal、2个provision确切名称后，7项离线门禁通过退出0。首次沙箱运行因Windows临时目录ACL失败，正常用户权限复跑通过，未削弱测试。探针/CI离线测试15项退出0：实际工具版本常量及历史查询改V21、失败文件改V22（这不等于真实启动验证）。

在这个RED时间点，F01/F02为FAIL（目标缺陷已实际复现），F03/F04为NOT_RUN（尚未实施对应工具与用例），F05为FAIL（真实CI已执行且失败）；随后真实修复结果在本文后续章节单独记录，不把RED写成GREEN。

修复候选本机验证：系统Maven `mvn -B -ntp test`最终退出0，66单元（新增CoreCommandsOutcomeTest 3）、0F/E/S，编译47个测试源。此前沙箱回环被拒导致网络测试错误，停止该次进程退出1；正常权限第一次又遇到并行编写中的helper参数未补完编译失败，全部源码完成后复跑66通过。聚焦3单元曾因Spring两个422枚举别名直接比较失败，改为HTTP数值422并仍精确比较code/message，非业务语义放宽。

Windows Wrapper独立`.\mvnw.cmd -B -ntp test`退出1，`.m2` MinIO缓存AccessDenied、零测试，不能与系统Maven混算。前端`npm ci --no-audit --no-fund`沙箱EPERM退出1，正常权限复跑0；`npm run lint`退出0（9项既有warning）、完整JSON Vitest213/0F/E/S退出0、`npm run build`TypeScript/Vite退出0，`python tools/verify_r11_asset_web_report.py local-evidence/r11-fix01/vitest.json`退出0。完整`python -m unittest discover -s tools/tests -v`最终116项退出0（新provision9及FIX门禁1），`python tools/validate_package.py`退出0，仍明确184业务规划验收NOT_EXECUTED。

Push RED制品已读取到被忽略的`local-evidence/r11-fix01-red/backend/target/failsafe-reports`，XML汇总确认为149/3F/0E/0S；PR同样失败，实际checkout/build为`fec1dd0e53655ca0cc9dc823f4ff25a31a7c5de5`，不是分支head。原前端、工具、故障与浏览器五Job通过。新修复真实GREEN必须另查，不用这次RED的旧层通过代替整轮验收。

未合并PR9/10、未部署、未恢复定时任务、未修改默认分支/保护/可见性，未进入M12。

## 第一修复候选的真实失败（保留）

本地/远端代码候选`4036eaf4a017632f0815697c3e51d6ed957efe2c`的[Push38954965238](https://github.com/fangzhiy/Test365Alm/actions/runs/38954965238)和[PR38954971007](https://github.com/fangzhiy/Test365Alm/actions/runs/38954971007)均为failure，仅server失败、其余五Job成功。PR实际checkout/build `0cd063002983e4495affd1567f5bfb3d03478433`，父提交为base d213e47和head4036eaf，tree均`2fa59c7a42f50609a3e89503f7edce987bed87e4`。

真实PR报告：66单元通过；152集成中2失败、0错误、0跳过。`AssetDatabaseIT`16、`AssetTerminalOutcomeIT`5、整个`OidcCallbackSecurityIT`24均通过；两个`AssetProvisioningUpgradeIT`方法在首次正式provision返回1而非0，尚未进入升级断言。不能将静态审查或其他150个集成通过扩大成F03/F04通过。PR backend制品12070352219已下载至被忽略的`local-evidence/r11-fix01-candidate1/backend`。随后根据此失败定位正式工具入口，不绕过权限或换回helper授权。V21正常迁移/V22精确失败探针均已实际通过。

自修定位：容器配置隔离误将`PGSERVICE`/`PGSERVICEFILE`设为空串；PG17 libpq将其视为已设置的服务/路径而在连接前失败，并不等同unset（[官方实现](https://raw.githubusercontent.com/postgres/postgres/REL_17_STABLE/src/interfaces/libpq/fe-connect.c)，`parseServiceInfo`）。修为容器内`env -u`真正移除偏转项。原离线mock只检查参数存在，不能证明真实libpq可连接；更新断言先RED再GREEN，并在原真实升级用例中增加同一ownedPG、仅`SELECT current_user`的空变量失败/unset成功对照。另补闭集阶段/SQLSTATE诊断，绝不打印原SQL、stderr或secret。两项新增安全诊断离线测试后工具11项退出0、系统Maven测试编译退出0；真实对照仍以随后CI为准。权限/ACL SQL未因该失败放宽。

第二候选`9d5be0ca975e8d241e556cca84a73e0f9c18a066`的[Push38955997267](https://github.com/fangzhiy/Test365Alm/actions/runs/38955997267)/[PR38956004394](https://github.com/fangzhiy/Test365Alm/actions/runs/38956004394)均仅server失败。PR checkout/build `13bc149c16210af0f381de7d9c8b70dadfe0f67a`；66单元、152IT中151通过/1失败/0error/0skip，其他五Job均success，完整14浏览器及独立UI1通过。真实同容器libpq对照及非空升级/正式LOGIN/CLEAN rev3均已通过，证实第一失败的环境隔离修复。唯一剩余失败是已有LOGIN错误密码的反例却返回0：容器127.0.0.1匹配trust，连接成功不能证明秘密参与认证。保留此失败，不删除wrong-password断言。

后续收紧正式工具：两次连接显式要求`require_auth=scram-sha-256`，并固定`PGREQUIREAUTH`；不接受父环境改成none，不支持该参数的旧客户端也必须拒绝，不能只靠未知环境变量被忽略。测试专属数据库初始化host SCRAM，保留local Unix无秘密对照，检查实际HBA和trust被强制SCRAM拒绝；不修改任何用户数据库/HBA。依据[PG17连接认证要求](https://www.postgresql.org/docs/17/libpq-connect.html#LIBPQ-CONNECT-REQUIRE-AUTH)。本机118项Python在干净9d5be0c实际退出0；这不替代新增认证修正后的完整验证。

## 第三候选：业务全部通过，旧门禁遗漏新名称（保留）

代码`c08b7ad916e8b3b44b9a1315c0bbca68e56f1356`的[Push38957097675](https://github.com/fangzhiy/Test365Alm/actions/runs/38957097675)/[PR38957103747](https://github.com/fangzhiy/Test365Alm/actions/runs/38957103747)仍为failure，仅server失败。PR实际checkout/build为`12ee1cd1af32a57db838cb3451fdb5f9b8a6de97`，ref=`refs/pull/10/merge`；不是分支head。单元66、集成152均0F/E/S，包括正式provision的两个真实场景、所有资产DB/terminal/HTTP，以及原模块回归。其他五Job通过，浏览器14+独立项目UI1通过。原`/me401`本次未复现，根因仍未证明。

剩余失败准确位于`Require real HTTP OIDC callback integration report`：`verify_r03_oidc_http_report.py`闭集仍是23个名称，新`realOidcAssetHttpTerminalCompleteFreezesRejectionAndReauthorizesReplay`被判unexpected。M05专项27个IT门禁已通过，但不能代替旧OIDC门禁。修复须把实际第24项加入原闭集并增加源码/名单一致性及缺失、重复、F/E/S回归，不能改成忽略未知用例或跳过门禁。

PR backend制品[12071356538](https://github.com/fangzhiy/Test365Alm/actions/runs/38957103747/artifacts/12071356538)已核验到被忽略的`local-evidence/r11-fix01-candidate3/backend`。正式工具真实升级身份为`r11_fix_scanner_f8a11503b17740d283eb6c4903b58180`：LOGIN/INHERIT/scanner membership=true，SUPERUSER/BYPASSRLS/CREATEDB/CREATEROLE/REPLICATION/runtime membership=false。非空升级前包含asset1、revision2、upload2、scan2、attachment1、audit13、asset Outbox10、core idempotency5；逐字段旧行/对象身份保留，之后真实CLEAN发布rev3，外项目及未确认upload的CLEAN写入被精确拒绝。wrong-password、trust不满足SCRAM、重复配置不扩权、既有group LOGIN和不安全role拒绝均已执行通过。

本机在c08b7ad复跑系统`mvn -B -ntp test`退出0（66/0F/E/S），完整Python119项退出0、规划校验0。CI实际Temurin21.0.12.1+1、Maven Wrapper3.9.16/Python3.12.15；本机Java17.0.2/系统Maven3.9.14不同。该候选业务测试通过仍不等于F05通过，待门禁修正后的完整Push/PR。

门禁自修采用测试先行：`python -m unittest tools.tests.test_r03_ci_evidence -v`原代码11项/3失败；只补名称后仍4失败，暴露重复testcase和汇总为零却含failure/error/skipped子标记的假报告未被拒绝。最终只改该校验器和对应测试：闭集24与真实Java `@Test`方法名单一致，真实case计数/唯一性检查及逐case非通过检查。专项11/11退出0，全Python123退出0；首次沙箱temp ACL失败不算功能RED。代码提交`561062ba15a9e2695d1c26e66d84fe91b316bf11`正常push、远端SHA一致。使用该校验器重新检查第三候选的真实XML，OIDC24及M05专项27门禁均退出0；最终仍须本代码的新Push/PR实际通过，不能把重新解析旧XML当成新CI。

## 实际修复及文件对应

| 范围 | 修改文件及原因 |
|---|---|
| F01提交完整性 | 新增`apps/server/src/main/resources/db/migration/V21__asset_revision_commit_integrity.sql`；SECURITY INVOKER、固定search_path、延迟到提交检查真实连续revision集合/allocator/current，不依赖可伪造GUC。`AssetDatabaseIT`新增合法runtime/MEMBER六类独立事务及并发rev3/rev4对照。 |
| F02冻结错误 | `asset/AssetService.java`在感染拒绝的业务事务内保存terminal outcome；`core/CoreCommands.java`增加显式版本化、受限status/code/safe message错误信封，旧成功JSON不变。新增`CoreCommandsOutcomeTest`、`AssetTerminalOutcomeIT`以及`identity/OidcCallbackSecurityIT`的一个真实HTTP矩阵。 |
| F03正式身份 | 新增`tools/provision_asset_scanner_role.py`及唯一SQL源`.sql`、离线`tools/tests/test_asset_scanner_provision.py`。明确授权operator、最小子进程环境、实际SCRAM、秘密不入argv/日志、不自动旋转和不安全角色拒绝。`AssetTestInfrastructure.java`只负责证明Testcontainers资源归属并调用正式工具，不以helper DDL代替这条验收。 |
| F04非空升级 | 新增`AssetProvisioningUpgradeIT.java`两个方法：真实非空V20全行保持、再次迁移0、正式LOGIN重复核验、真实CLEAN rev3、越范围/未确认CLEAN拒绝；另一方法保留group LOGIN并验证不安全角色/错误秘密/会话日志边界。 |
| V21/V22联动 | `PrincipalUpgradeIT`、`ReleaseCycleDatabaseIT`、`RequirementMigrationUpgradeIT`、`TestCaseMigrationUpgradeIT`更新实际执行迁移数量；`verify_r02_migration_failure.py`、对应离线测试及`test_ci_workflow.py`、`.github/workflows/ci.yml`实际运行V21成功/V22精确失败。未改旧迁移或旧夹具内容来伪造升级。 |
| 必需门禁 | `verify_r11_asset_report.py`、`test_r11_asset_evidence.py`加入本FIX确切方法；`verify_r03_oidc_http_report.py`、`test_r03_ci_evidence.py`修正24项闭集及逐case证据。缺失、重复、失败、错误、跳过均拒绝。 |
| 文档 | 本完整输入、执行记录、development-status、README、AGENTS、assets契约、ADR025记录边界、操作方式、真实失败和验证版本；保留原R11记录。 |

本轮相对起点共31个新增/修改文件、无删除；前端产品源码/浏览器用例、依赖版本、原V1—V20均未修改。系统Maven/Wrapper的区别、真实scanner/S3、旧模块门禁和全部有效回归继续保留。

## 可复核命令、结果与证据位置

以下命令根目录执行，除注明`apps/server`、`apps/web`。退出码按各自实际调用记录，不能把一次后续成功覆盖此前失败。

| 命令/环境 | 实际结果 | 证据 |
|---|---|---|
| `python -m unittest discover -s tools/tests -v`，Windows正常用户权限 | 最新门禁代码123项，exit0；含provision12及新增OIDC门禁4方法 | 本机执行输出；对应CI planning-evidence/Python报告 |
| `python tools/validate_package.py` | exit0；184业务规划验收仍NOT_EXECUTED，不提升WBS/G0状态 | planning-evidence |
| `mvn -B -ntp test`，apps/server系统Maven | c08b7ad：66，0F/E/S，exit0；561062b仅Python门禁变化 | `apps/server/target/surefire-reports`，本机03:46:54+08最终输出 |
| `.\mvnw.cmd -B -ntp test`，Windows Wrapper | exit1，缓存AccessDenied，零测试，不作PASS | 上述本机过程；不同于系统Maven |
| `npm ci --no-audit --no-fund`，apps/web | 正常权限exit0；沙箱EPERM exit1独立保留 | 本机输出；CI frontend-evidence |
| `npm run lint` / `npm run test:run -- --reporter=verbose --reporter=json --outputFile.json=../../local-evidence/r11-fix01/vitest.json` / `npm run build` | 各exit0；213测试，19文件，0F/E/S；TypeScript/Vite成功，9既有lint warning | `local-evidence/r11-fix01/vitest.json`及CI frontend-evidence |
| `python tools/verify_r11_asset_web_report.py local-evidence/r11-fix01/vitest.json` | exit0，M05组件24/API5精确名称 | 本机JSON/门禁摘要 |
| CI `./mvnw -B -ntp test`、`./mvnw -B -ntp -Pintegration verify -Dbuild.commit=<实际GITHUB_SHA>` | 第三候选66单元/152集成全部通过；整体Job因旧门禁失败已单列，随后完整CI结果另列 | backend-evidence的unit-tests.txt/integration-build.txt、Surefire/Failsafe XML、checkout-sha.txt |
| CI `python tools/verify_r02_readiness.py ...`、`python tools/verify_r02_migration_failure.py ...` | 第三候选两Job通过：相同后端数据库停/恢复、V21启动成功后独立V22精确失败 | readiness-recovery-evidence、migration-failure-evidence，保留资源归属清理 |
| CI `npx playwright test ... --grep "real Keycloak UI project flow" --workers=1 --reporter=list`、`npm run test:e2e` | 第三候选独立1+完整14全部通过；workers1/retries0，M05页面仅1，不能把14全部算M05 | r03-oidc-browser-evidence的两个日志、JUnit、browser-status、脱敏timeline |
| `python tools/verify_r03_oidc_http_report.py <真实下载XML目录>`、`python tools/verify_r11_asset_report.py <同目录>` | 561062b门禁针对c08实际报告均exit0，分别24个OIDC方法及27个M05精确场景；不冒充新CI | `local-evidence/r11-fix01-candidate3/backend`及原制品链接 |
| `git diff --check`；对本轮改动进行private-key/GitHub-token/AWS-key模式扫描 | diff exit0；rg exit1表示无匹配，不等于全量安全审计；跟踪文件无真实.env/私钥 | 本机命令输出及完整31文件清单 |

执行环境：本机Windows11/PowerShell7.6.5、Java17.0.2、系统Maven3.9.14、Node26、Python3.12.8；CI Ubuntu/Temurin21.0.12.1+1、Wrapper3.9.16、Node24.21.0、Python3.12.15。Maven编译目标仍17（既有偏差未擅自调整）。真实隔离PostgreSQL17.11、SeaweedFS4.48、ClamAV1.5.4及原固定digest未升级。owner仅夹具/迁移/核验，业务使用runtime，扫描证明由正式配置的独立scanner连接。

## 已验证代码提交及验收映射

本地/远端代码SHA均`561062ba15a9e2695d1c26e66d84fe91b316bf11`；[Push38958095983](https://github.com/fangzhiy/Test365Alm/actions/runs/38958095983)和[PR38958100160](https://github.com/fangzhiy/Test365Alm/actions/runs/38958100160)的planning-tools、web、server、readiness-recovery、migration-failure、oidc-browser六Job均success。PR实际checkout/build `b659b9ee9b8c52e086cad8fba55122a2aaeab1c4`，`refs/pull/10/merge`；父提交为base `d213e47bebbe0f89522be48c3e58003d008cdf06`与head561062b。两个树均`7470df30ff696d4e974067ebb3d20b375ead64be`，没有把merge-ref写成head。

已下载本代码PR制品到被忽略的`local-evidence/r11-fix01-code/{backend,frontend,planning,browser}`。远端backend制品[12070924892](https://github.com/fangzhiy/Test365Alm/actions/runs/38958100160/artifacts/12070924892)、browser[12071163285](https://github.com/fangzhiy/Test365Alm/actions/runs/38958100160/artifacts/12071163285)；同run的frontend/planning/readiness/migration制品均存在。Surefire XML 66、Failsafe XML 152，0F/E/S；M05精确27个IT，较原18新增9，另新增3个core error单元；OIDC全类24中M05只有3个综合HTTP矩阵。Vitest JSON213全部通过，Python报告`Ran 123 tests ... OK`（较原106新增17），完整Playwright14/独立UI1均exit0，无重试/跳过。

| 验收 | 状态及确切证据 |
|---|---|
| F01 | PASS。`AssetDatabaseIT.publishedAssetAllocatorAndCurrentPointerCannotMoveBackwardOrGap`：真实runtime+MEMBER、rev1/rev2，对allocator调小/+100、allocator+pointer回退、仅pointer回退、无revision advance、真实CLEAN修订gap独立事务提交均被目标23514拒绝，八表无副作用；同方法正常并发rev3/rev4连续唯一。旧独立pointer防线保留。 |
| F02 | PASS。`AssetTerminalOutcomeIT`5方法和`OidcCallbackSecurityIT.realOidcAssetHttpTerminalCompleteFreezesRejectionAndReauthorizesReplay`：真实clamd感染首次/同key重放均422 FILE_INFECTED且safe message相同；newkey409 UPLOAD_REJECTED；不同hash409 IDEMPOTENCY_KEY_REUSED；无重扫/scan/audit/Outbox/版本副作用。真实暂停scanner得到503 SCAN_UNAVAILABLE/SCAN_FAILED，原key恢复PUBLISHED，成功重放不变。原会话撤权/tenant过期404、disabled在HTTP过滤器403，均不返回旧终态；Service直接调用不可见统一404。 |
| F03 | PASS。`AssetProvisioningUpgradeIT.scannerProvisioningPreservesExistingGroupLoginAndRejectsUnsafeRoles`及`upgradedDatabaseUsesProvisionedRestrictedScannerLogin`实际调用仓库Python/SQL。最新升级child current_user=`r11_fix_scanner_2165526d01764240a35465e703de9c24`，LOGIN/INHERIT/scanner=true；SUPERUSER/BYPASSRLS/CREATEDB/CREATEROLE/REPLICATION/runtime=false。正确已确认CLEAN能写，外项目/未确认CLEAN精确23514拒绝；原group LOGIN仍可登录，重复不扩权，错误密码与trust拒绝，秘密/verifier日志断言通过。 |
| F04 | PASS。同`upgradedDatabaseUsesProvisionedRestrictedScannerLogin`在V20真实业务生成asset1/revision2/upload2/scan2/attachment1/audit13/Outbox10/idempotency5，所有public旧表逐行逐字段快照V21迁移前后相等，再迁移0。rev1/rev2字节、hash/MIME/size/object key、current=rev2/next=3、旧link和成功幂等rev1均保留，正式新scanner发布rev3/next=4后旧记录仍在。旧V19→V20非空升级方法继续通过。 |
| F05 | 代码CI PASS：上述Push/PR六Job、全部精确报告门禁、实际V21成功/V22失败、真实PG/S3/clamd和14+1浏览器通过。最终文档交付会产生新SHA；其Push/PR实际结果在交付回复核对，不预先声称通过，也不为回写最新CI反复提交。 |

该代码PR的readiness记录同一PID4111：startup live200/ready200，停库live200/ready503，恢复ready200无重启；实际监听`::ffff:127.0.0.1`。迁移探针确认正式V21完成，独立V22目标失败、子进程exit1是该负向场景预期，整个验证脚本exit0；资源清理只限本轮所有权清单。这里的通过不等于生产上线、外部审核通过或整模块完成。

## 剩余边界

本机Docker29.7.2客户端存在，但Linux engine命名管道不可用：真实PG/S3/clamd/Keycloak本机复跑BLOCKED_LOCAL，UbuntuCI不是本机通过。Wrapper缓存权限和npm沙箱EPERM分别保留，未修改系统全局权限、用户Docker/HBA或任何生产环境。技能通用definition-of-done引用文件在本机不存在，完成定义直接使用仓库AGENTS与用户F01—F05，不降低验收。

未知`/me401`（历史Push38946999225）本轮未复现，根因仍未证明；不改认证、不加登录/Playwright retry、不提高超时。原1fcbda1 scanner恢复失败保留在原R11记录；本轮三次候选失败及修正均在上文，不静默删除。生产scanner运维/secret轮换、对象服务安全评审、GC/配额/完整DoS及全量漏洞审计未交付，不宣称完整M05或兼容全部旧ALM。PR9/10保持Open，不合并、部署、恢复定时任务、改默认分支/规则/可见性或开始M12。
