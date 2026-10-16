# R09-CORE-BATCH02 执行记录

日期：2026-10-16。输入全文：[原文](../inputs/R09-CORE-BATCH02.md)。实际仓库 `fangzhiy/Test365Alm`，本地位于 `jihua/Test365Alm_ProjectPackage/Test365Alm`。仅本批次记录连续定位/修复，不拆成多个 FIX 轮次；无自动合并、部署、定时任务恢复或默认分支配置变更。

## 起点与阶段 A

开始时工作区干净，分支 `feat/r08-m04-m11-001`，本地与真实远端 head 都是 `740ac21980b41a8ca49be616e7df4a4b8904e881`。实际 fetch 成功，PR #7 Open、未合并、Mergeable=true，base `chore/r01-status-001@1e49617f283ffa54673d590a1aba3e72fcfbe14e`；PR #1—#6 已合并。重新读取历史交付 Push38213436949/PR38213441555，均 completed/success，不沿用旧“未合并”快照。

读取 AGENTS、当前状态、R08 全文/记录、M04/M10/M11、领域模型、需求/用例/执行/追踪契约与实际源码。代码审查与安全技能用于范围内自审，另有只读独立审查，不把内部自审称为外部审核。

发现并修复 NUMBER 零边界：precision==scale 时零不需要整数位，旧实现误拒绝 `0/-0`。先执行 `mvn -B -ntp test '-Dtest=FieldRulesTest'`，退出1、4测试中1失败，确切 `Decimal exceeds precision or scale`；最小修复只影响零的整数位计算，小数位限制不变。完整 `mvn -B -ntp test` 后退出0，42测试无失败/错误/跳过。

补真实数据库 FAIL→PASS→关联失效与需求修订变化覆盖证据；保留原未执行/活动重跑规则，字段修订 audit/Outbox 故障完整回滚；实际 OIDC 旧会话直接访问字段/追踪的停用拒绝（403/FORBIDDEN），未先调用 /me。R08 精确名称门禁要求新增覆盖方法。

Attempt A1：新增测试一处误用了 rerun 的返回类型，testCompile退出1，修正为现有 AttemptView；没有改产品接口。Attempt A2：本机真实集成命令 `mvn -B -ntp -Pintegration verify '-Dit.test=CoreMetadataTraceDatabaseIT,OidcCallbackSecurityIT'` 退出1，Failsafe两个类错误，原因Docker Desktop Linux命名管道不存在。`docker version` 确认 daemon 不可达（不是数据库行为通过）；本机集成 BLOCKED，继续用既有隔离 Ubuntu CI。没有删除/跳过测试或连接开发库。

本机前端 `npm run test:run`、`npm run build`、`npm run lint` 均退出0，145测试通过；普通既有React lint warnings保留，不扩成依赖升级。根目录 Python unittest80通过、validate_package退出0；规划状态仍 NOT_EXECUTED/P0 inputs missing。技能引用的本机公共 references 文件缺失，沿用本仓库 AGENTS/验收作为完成门，不据此放宽要求。

阶段 A PASS：代码 `aaf18e5726037afe5c6cf693ca1eb7449faa8141`，Push [38859691958](https://github.com/fangzhiy/Test365Alm/actions/runs/38859691958)、PR [38859696614](https://github.com/fangzhiy/Test365Alm/actions/runs/38859696614) 六Job均success。PR实际checkout `218692a30ded9e44a91a3adfc89ba1064cc6cc75`，42单元/98集成/145前端/80Python/11浏览器，零失败/错误/跳过，另独立项目UI1通过。PR7仍Open，未合并。门通过后才从该head创建 `feat/r09-m10-001`；新PR依赖PR7，base为 `feat/r08-m04-m11-001`。最终交付SHA/CI仅回复收口，避免自引用文档循环。

## 阶段 B：已验证代码与交付边界

- 新分支 `feat/r09-m10-001` / [PR #8](https://github.com/fangzhiy/Test365Alm/pull/8)，base `feat/r08-m04-m11-001@aaf18e5726037afe5c6cf693ca1eb7449faa8141`。PR #7/#8 都 Open、未合并，默认分支仍 `chore/r01-status-001@1e49617f283ffa54673d590a1aba3e72fcfbe14e`。只更新PR描述，不修改base/保护/可见性。
- 代码验证 SHA（本地=真实远端）：`b8134c330a20260200ce69735578757ba505b443`，父 `5537c2a80ce4e11540f8788ac9c47e74418991f1`。普通git push，无API另造提交、无强推。PR实际checkout `65cbf404f04dc4bb6e21e0c3a75602dd050b0ed5`（`refs/pull/8/merge`），父为 `aaf18e5...`、`b8134c3...`，tree `4dcfc3749e379df2a71d11f52616965f6cea72a6` 与代码head完全相同，不能将merge SHA写成branch SHA。
- 代码 [Push38865148036](https://github.com/fangzhiy/Test365Alm/actions/runs/38865148036) / [PR38865154723](https://github.com/fangzhiy/Test365Alm/actions/runs/38865154723) 均 completed/success，六Job `planning-tools/web/server/readiness-recovery/migration-failure/oidc-browser` 全部success。两条浏览器均12通过、无失败/错误/跳过，独立项目UI另1。下方数量对应同一代码基线，不借用旧运行证明新代码。
- 本记录冻结于上述代码和已有CI。包含本记录/README/ADR的最终交付SHA、最终Push/PR结果与checkout只在最终回复核对；不循环在文档追写自身SHA。没有宣称外部审核通过。

## 实际实现、修改文件及原因

- `contracts/defects.md` 先冻结内容字段、有限状态机、错误/强ETag、幂等作用域、来源/当前解决周期规则；[ADR023](../../adr/023-r09-defect-core-and-related-retest.md)记录设计与限制。
- `apps/server/.../defect/{DefectRules,DefectController,DefectService,DefectEvidence}.java`：真实HTTP、严格输入、稳定UUID/项目BUG编号、追加修订、typed来源、原M09明确复测、关闭重验、同事务审计/Outbox/冻结幂等。`project/ProjectService.java` 加独立缺陷权限，租户管理员不获得隐式项目权。
- `V18__defect_core_slice.sql` 仅新增defect/revision/link/retest/allocator/typed Outbox、复合FK、初始/有限状态/来源快照与复测周期保护、runtime RLS/grants。V1—V17原文件不变；原Word/planning全量目标不变。`PrincipalUpgradeIT`、需求/用例升级测试只调整最新迁移数量，保留原数据与重放断言。`verify_r02_migration_failure.py` 及其离线/CI测试把独立失败探针顺延V19，正式V18必须先通过。
- `DefectRulesTest`、`DefectDatabaseIT`、`DefectMigrationUpgradeIT`、`identity/OidcCallbackSecurityIT`：规则、真实runtime数据库、非空升级及两个真实HTTP矩阵；没有mock登录/owner业务/RLS跳过。
- `apps/web/src/defectsApi.ts`、`DefectForm/Create/Detail/Attempt.tsx`、`DefectsPanel.tsx`、`FailedStepDefect.tsx`、`App.tsx`、原`TestExecutionPanel.tsx`：真实分页/内容/状态/历史/来源/复测；复用原execution API而非新执行引擎。独立范围与请求轮次、超时、取消/迟到、防丢草稿、原键未知重试、GET-only恢复；多个失败步骤按来源锁计数。`useCoreRequest.ts`只额外导出已有cancel供父范围失效。
- `DefectsPanel.test.tsx`、`defectsApi.test.ts`、`TestExecutionPanel.test.tsx`：确定性DOM输入、列表/创建互斥、直接失权迟到三分支、StrictMode/timeout/unmount、412与GET-only、两来源锁以及只序列化四字段。
- `apps/web/e2e/defects.spec.ts`：一个新增真实Keycloak普通MEMBER完整DOM流程、真实reload及独立VIEWER负向；脱敏事件时间线分开记录业务/清理异常，不上传trace/密码/token/表单值。
- `tools/verify_r09_defect_report.py`、`test_r09_defect_evidence.py`、原OIDC/浏览器/核心门禁及`.github/workflows/ci.yml`：精确9DB+1升级+2HTTP+1UI测试名称，缺失/失败/错误/跳过/重复均拒绝，六Job不删改成更小套件；保存失败时报告和安全时间线。
- `README.md`、`AGENTS.md`、当前状态、本轮记录：实际启动/验证/权限边界与最小切片完成定义；输入全文已在阶段A真实发布，最终交付再次按SHA回读。

## 连续修复历史（不静默删除失败）

1. `ef32730` 先规则测试：缺类的RED编译退出1，随后2规则测试通过。`65f3e69...` 首次真实DB运行 [38861257600](https://github.com/fangzhiy/Test365Alm/actions/runs/38861257600) server失败：fixture项目授权已有审计，测试将全部project audit误算为缺陷audit（期望2实际5）。限定`object_type='defect'`，不是删审计；`1c9ff09...` [38861588313](https://github.com/fangzhiy/Test365Alm/actions/runs/38861588313)六Job成功，44单元/105集成，缺陷7DB。
2. 独立自审发现：创建失权未传播父范围、REOPENED仍显示旧PASS、多来源表单布尔锁互相释放。补受控组件/实际DB回归。`f56bbd11d317ab5b48d9cdb0de9af2b17d406170` [38863913097](https://github.com/fangzhiy/Test365Alm/actions/runs/38863913097)实际110集成中1失败，确切 `REOPENED cannot advertise previous cycle PASS`（expected false/actual true）；其余包含非空升级、runtime SQL边界、OIDC19均通过。`d9c9f49`按当前状态限制verification，未改关闭规则或旧迁移。
3. 创建失权组件旧实现RED（152总测试中1失败，父详情仍在），修复后通过。增加来源ID锁集合与两FAIL表单受控Promise，任一完成不释放另一pending/unknown。随后自审发现旧列表可覆盖新创建结果，新增互斥回归在旧实现RED，修复为列表与创建互斥，未降低权限或无条件finally。
4. `5537c2a...` [Push38864286515](https://github.com/fangzhiy/Test365Alm/actions/runs/38864286515) / [PR38864336575](https://github.com/fangzhiy/Test365Alm/actions/runs/38864336575) browser失败，其余五Job成功。首次业务失败点为编辑步骤：请求把只读revision元数据发给严格HTTP，收到`Unknown request field`而仍v1；不是cleanup/merge不同业务代码。原11浏览器通过、新M10失败。补精确四字段组件RED；在草稿和HTTP两层投影可写内容，保留后端严格拒绝。抽出真实data helper后mock缺导出导致组件12失败，改为partial mock保留真实helper，39专项通过。最终`b8134c3...`完整160前端与真实12浏览器均通过。
5. Python离线门禁首次RED是未加入M10必需UI名称，补门禁及正/负合成报告；sandbox临时目录权限曾导致27个fixture错误，非业务结果，授权普通环境重新执行85通过。最初TS测试多传`exact`选项编译退出1，移除不支持选项后类型/构建通过。保留这些事实，不把先失败后修复写成从未失败。

## 工具、配置和实际命令

本机Windows11：Java17.0.2、系统Maven3.9.14、Wrapper3.9.16、Node26.0.0/npm11.12.1、Python3.12.8。CI Ubuntu：Java21.0.12.1、Wrapper3.9.16、Node24.21.0/npm11.19.0、Python3.12；编译release17不变。依赖/锁文件未整体升级。PostgreSQL17.11镜像使用既有固定digest `sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232`，Testcontainers独占数据库，Flyway owner与runtime分离；不加载日常.env。开发配置/停止说明沿用README，默认回环限制不变。

| 实际命令（相应目录） | 退出码/结果 | 证据 |
| --- | --- | --- |
| server `mvn -B -ntp test` | 0；44/0/0/0 | 本机Surefire；本轮再次编译新增IT源码 |
| server `.\mvnw.cmd -B -ntp test` | 独立执行0；44/0/0/0 | 本机Wrapper，非冒用系统Maven退出码 |
| server `mvn -B -ntp -Pintegration verify '-Dit.test=DefectDatabaseIT'` | 1；本机Docker unavailable，业务BLOCKED | 本机Failsafe初始化错误，不计CI通过数量 |
| CI `./mvnw -B -ntp -Pintegration verify "-Dbuild.commit=${GITHUB_SHA}"` | 0；Surefire44/Failsafe110，零失败/错误/跳过 | backend-evidence：XML、integration-build.txt、checkout-sha.txt；PR构建commit为实际merge SHA |
| CI单独执行旧`ManualExecutionMigrationUpgradeIT`专项 | 0；3通过（另一次执行，不叠加110） | execution-upgrade-tests.txt及旧升级精确门禁 |
| 根目录 `python tools/verify_r09_defect_report.py apps/server/target/failsafe-reports`（CI以相对路径调用） | 0；9DB/1升级/2HTTP名称均存在且成功 | defect-r09-report.txt及完整Failsafe XML |
| web `npm ci` | 0；本机127包，锁文件不变 | 真实安装输出；CI独立安装通过 |
| web `npm run test:run` | 0；14文件160测试 | frontend-evidence/前端测试报告；其中新增M10/交接15，不冒称160均新增 |
| web `npm run lint` / `npm run build` | 分别0；TypeScript及Vite成功，保留9项既有warning | frontend-evidence；新Form fast-refresh warning已最小修正，无依赖升级 |
| 根目录 `python -m unittest discover -s tools/tests -v` | 0；85（新增门禁5），合成负向输出的FAIL是被测预期，不是套件失败 | planning-tests.txt含stderr；本机再次85通过 |
| `python tools/validate_package.py` / `python tools/p0_health_check.py` | 分别0；规划完整，产品NOT_EXECUTED/P0 B01—B05仍缺 | planning-validation.txt/planning-p0-health.txt；不等于业务验收 |
| CI Playwright完整套件，workers=1/retries=0 | 0；12/0/0/0，其中M10 1 | r03-oidc-browser-evidence/playwright-results.xml、defect-ui-context-full.json；项目独立对照另1 |
| CI真实停库恢复、故意失败迁移和预存资源对照 | 全部0；V18成功后专属V19故意失败并核验原因 | readiness-recovery-evidence/migration-failure-evidence；资源manifest归属校验不绕过 |
| 本机`docker version` | 单独1；client29.7.2，desktop-linux engine命名管道不存在 | 本机真实数据库与浏览器复跑BLOCKED；CI不是此本机daemon |
| `git diff --check`、原迁移/Word/planning增量检查 | 0；无空白错误，V1—V17/planning/deliverables无修改 | git差异；正式仅V18新增 |
| `git grep -l -E '(ghp_…|github_pat_…|AKIA…|-----BEGIN …PRIVATE KEY-----)'` | 1表示无匹配，非“扫描失败即通过” | 仅tracked文件；tracked .env只有.env.example；新测试秘密为专属合成值/运行生成，不是真实凭据 |
| `npm audit --omit=dev --json` | 1；本机npmmirror不提供security endpoint（404） | 不声称此检查通过 |
| `npm audit --omit=dev --registry=https://registry.npmjs.org --json` | 0；production依赖0已报告漏洞 | 仅prod npm范围，不等于完整安全审计；未修改全局registry或依赖 |

## L01—L10 按原定义映射

以下L01—L09 PASS均对应代码`b8134c3...`的上述CI；代码报告可在相应Job及制品独立复核。L10最终文档交付仅在最终回复确认，不在尚未产生该提交时预先声明。

| 条件 | 结果/具体证据 |
| --- | --- |
| L01 空库/非空升级保留 | PASS：各Testcontainers空库V1—V18；`nonemptyV17RequirementsTestsRunsAndTraceSurviveV18AndRemainUsable`先真实runtime生成非空需求/用例/运行/trace/幂等，原表完整JSON快照不变，再迁移0，升级后原读/旧键重放与创建来源缺陷合法 |
| L02 MEMBER HTTP创建与原子证据 | PASS：`realOidcDefectHttpMemberEtagsCsrfStrictInputAndViewerBoundary`及`memberCreatesImmutableDefectAndFrozenReplayWithAtomicEvidence`；真实过滤链/会话/runtime，独立owner读业务/审计/Outbox/idem核验，不用owner执行业务 |
| L03 不可变编辑/并发412 | PASS：`concurrentEditsAndTransitionsHaveOneExactStaleLoser` 两独立事务一成功一确切412/STALE_VERSION，修订/事件计数和当前获胜内容；历史旧标题仍读、UPDATE/DELETE/TRUNCATE拒绝 |
| L04 状态机与CLOSED | PASS：`lifecycleRejectsIllegalTransitionsAndClosedHistoryCannotBeRewritten`合法路径含REOPENED，非法409无副作用，CLOSED编辑409；`legitimateRuntimeWriterCannotForgeInitialStateAppendCommittedSourceOrAlterSnapshot`初始非法CLOSED确切23514，非任意异常 |
| L05 typed真实FAIL来源 | PASS：`failingStepSourceAndExplicitRetestsPreserveFailureAndRequireRelatedPass`及第二M10 HTTP矩阵，真实run/attempt/manifest/test revision/step/需求冻结；错误step/同租户另一项目/跨租户404无副作用。活动来源SOURCE_NOT_FINISHED、PASS来源NOT_FAILED_SOURCE；合法writer错误快照与已提交追加分别精确拒绝 |
| L06 VIEWER/旧会话失权 | PASS：第一M10 HTTP矩阵含有效CSRF的create/edit/transition403，未入项目tenant admin404、匿名401、原会话撤权/降权/停用/租户成员过期直接请求，无先/me；runtime viewer SQL写0，独立owner快照无非法副作用；UI有独立VIEWER对照 |
| L07 audit/Outbox/幂等 | PASS：`auditAndOutboxFailuresRollbackCreateEditAndTransitionExactly`六项REVOKE INSERT明确permission denied，非空历史/全部相关行前后相同，恢复后成功；`concurrentSameKeyCreationAndBoundedPagesHaveNoDuplicateNumbers`同键两个事务一个结果/编号、异键唯一、异内容409及冻结重放 |
| L08 明确相关PASS验证与原FAIL | PASS：FAIL复测不满足、活动不满足、明确相关PASS可关闭并读原FAIL/PASS；`unrelatedPassActiveRetestAndPreviousResolutionCannotVerifyCurrentCycle`覆盖未绑定PASS/旧解决周期/REOPENED/新RESOLVED/后续活动或无关PASS均不满足，新周期合法对照通过 |
| L09 Keycloak真实页面 | PASS：`real Keycloak UI member creates defect from FAIL retests PASS closes and preserves history`，两条CI均真实DOM、roles恰为PROJECT_MEMBER、原FAIL→来源创建→内容编辑→IN_PROGRESS→RESOLVED→缺陷复测PASS→CLOSED→reload→原FAIL/PASS/状态快照；独立VIEWER无写入口及真实CSRF403 |
| L10 最终交付六Job与门禁 | 代码基线两条六Job PASS；本记录提交时最终文档交付CI尚未产生，最终交付项NOT_RUN于此冻结快照，由最终回复提供真实交付SHA/两条CI/checkout后收口。精准门禁的missing/failure/error/skip/duplicate负向已实际Python通过；不以旧成功覆盖最终提交 |

## 剩余限制和停止点

本机Docker engine仍不可达，真实数据库/浏览器本机复跑BLOCKED，由已通过Ubuntu隔离CI提供业务证据；不改系统默认context/全局权限。公共DevTools MCP未配置，使用已有真实Playwright，不伪称手动DevTools验证。目标运行时Java21/Node24已在本轮CI验证，编译target17为原ADR基线，不假称本机同版本。

M01/G0旧ALM授权/版本/样本与P0输入未补；生产部署、完整安全/性能/兼容/恢复验收未执行。M04业务自定义值/动态表单、M05工作流设计器、完整M11异步投影、完整M10附件/通知/SLA/树/批量/外部同步/AI/报表仍未交付。32模块/WBS/原AC状态不改；最小切片不等于完整模块。内部测试与自审不是外部审核。最终交付核对后停止于审核，不合并PR7/8、不部署、不恢复定时任务或进入下一模块。
