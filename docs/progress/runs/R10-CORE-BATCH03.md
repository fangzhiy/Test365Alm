# R10-CORE-BATCH03 连续实施记录

输入全文：[R10](../inputs/R10-CORE-BATCH03.md)。范围：主线事实纠偏、M06发布/周期/typed范围/冻结质量快照第一切片。内部实施和自审不代表外部审核。本文持续记录红绿结果，最终交付SHA和最新CI在执行回复核对，不循环提交文档自身SHA。

## 阶段 A / 实际起点

2026-10-17 本地起始 `feat/r09-m10-001@32e13d86731b0e2bc5a7d844c94a6f471f9a941e`，工作区干净。GitHub实际默认 `chore/r01-status-001@a2419e292c004b2cd4c7278bdaa40b1ce3442a22`。PR7/8已merged，merge分别 `e908b8e9f23be65efa95ecdf358229406d9f0fe4`、`a2419e292c004b2cd4c7278bdaa40b1ce3442a22`；无后续主线提交或R10分支。首次普通fetch失败 `getaddrinfo() thread failed to start`，获准正常网络执行 `git fetch origin --prune` 退出0，实际更新远端跟踪引用。没有回退/强推。新分支 `feat/r10-m06-001` 从真实主线建立。

读取AGENTS、README、当前状态、R09全文/记录、M03/M04/M06/M07/M08/M09/M10/M11手册、相关契约、ADR和现有授权/业务/数据库/前端/门禁。原始输入从中转保存的完整原文归档，非摘要。

[主线38931708362](https://github.com/fangzhiy/Test365Alm/actions/runs/38931708362)为push，head/实际checkout都为`a2419e2…`。server117077060000、browser117077060441、web117077060473、migration117077060552、planning117077060630、readiness117077060692均success。tree `1478f5c3c43399ceeacaad1fb8d46bdd1ae5c87e`与R09交付相同。六制品均存在未过期。

数量：Surefire44、Failsafe110（M07 DB13/升级1；M09 DB23/升级3；M04/M11 DB10；M10 DB9/升级1；OIDC全类19中M10方法2）、Vitest160、Python85、完整Playwright12及独立项目UI1，失败/错误/跳过0。旧精确M07/M09/M11/M10门禁实际执行success。真实同PID停库live200/ready503→恢复200，回环`::ffff:127.0.0.1`，正式V18成功/专属V19失败识别/安全清理均通过。

本机smoke均对应未改业务代码的a2419e2：

| 实际命令 | 退出/数量/环境 |
| --- | --- |
| `mvn -B -ntp test`（系统Maven） | 首次沙箱Java attach管道拒绝，中断exit1；获准重跑exit0，44/0/0/0。不是Wrapper结果 |
| `npm run test:run` | 首次TEMP rename EPERM退出1/0测试；正常权限重跑exit0，160/14文件 |
| `npm run build` | 0，TypeScript与Vite通过 |
| `python -m unittest discover -s tools/tests -v` | 首次TEMP权限错误exit1（85测试/90fixture错误）；正常权限重跑exit0，85通过 |
| `python tools/validate_package.py` | 0；32模块/192WBS/160模块AC/24E2E完整，产品仍NOT_EXECUTED |
| `docker version` | 1，client29.7.2/desktop-linux；engine命名管道不存在，BLOCKED_LOCAL |

本机Java17.0.2、系统Maven3.9.14、Node26.0.0/npm11.12.1、Python3.12.8；主线CI Java21.0.12.1、Node24.21.0/npm11.19.0、Python3.12.15。编译目标17不改。阶段A无阻断，直接进入B；development-status/README/AGENTS顶部纠正主线已合并事实，R08/R09历史不删除。

## 阶段 B / 首轮实现和自审

先冻结 [Release契约](../../../contracts/releases.md)。正式新版本计划V19，旧V1—V18不改，失败探针顺延V20。仅ADMIN写，MEMBER/VIEWER读；范围冻结需求和用例修订；质量指标保留原始分子分母、版本与证据，历史不重算。

首批代码提交 `8688b0e`（后端/迁移/真实测试/ADR）、`742bcd2`（页面/精确门禁）、`db4409e3efa4d712d8ba3e6bc67b90b9e65069f8`（范围版本单次递增断言）。正常 Git push 退出0；新 [PR #9](https://github.com/fangzhiy/Test365Alm/pull/9) base真实默认分支，无Git Data API重建、无本地/远端父链差异。按db4409e远端回读输入全文、ADR024与精确DB报告门禁成功。

实际新增：Release/Cycle/typed scope/immutable snapshot、受限runtime ADMIN写权限、有限分页、日期/状态约束、真实跨模块指标、同事务audit/Outbox/幂等、工作台与真实Keycloak主流程。具体语义见 [ADR024](../../adr/024-r10-release-cycle-quality-snapshot.md) 和契约；不更新原32模块/WBS/产品验收状态。

### RED → GREEN / 自修复记录

- ReleaseRules 两项先因实现缺失RED，实施后GREEN；前端初始13项因实现缺失RED，新增行为后逐步补到21项；门禁初始模块缺失RED后5项GREEN。
- 后端编译曾因新HTTP测试缺少assertNotEquals导入失败，补导入后通过；前端3处联合泛型TS错误修复为明确Entity，不改变API。
- 自审修正snapshot INSERT占位符数量、关闭发布归档接受内容未变的完整PATCH、数据库触发器维护scopeVersion（0→1→2）、软删除需求拒绝入范围/覆盖、父记录锁保证日期/范围快照一致。
- 确定性RED暴露“已确认编辑+随后GET失败”丢失确认标签，增加独立确认状态；另暴露本地新增快照与后续游标页重叠重复，按ID合并分页。两项均保留回归，不删断言或增加retry。
- Python完整90项首次出现两个真实fixture不匹配：旧浏览器正例缺新增M06名称、迁移模拟history18而正式已19；更新对应真实前提后90通过。后续沙箱TEMP另一次执行5项产生41个权限fixture错误，正常权限复跑完整90退出0；权限错误不是产品失败也不伪装成功。

### 本机命令 / 首轮证据

| 命令 | 实际结果 |
| --- | --- |
| 系统 `mvn -B -ntp test` | exit0 / 46，全部源码及IT编译；不执行IT |
| `.\mvnw.cmd -version` | exit0 / Maven3.9.16，Java17.0.2；与系统Maven3.9.14分开 |
| 干净db4409e `.\mvnw.cmd -B -ntp test` | exit0 / Surefire46、failure/error/skipped0，报告`apps/server/target/surefire-reports` |
| `npm run test:run` | exit0 / 181，16files；新增组件18+API3，原160保留 |
| `npm run test:run -- --reporter=verbose --reporter=json --outputFile.json=../../local-evidence/r10/vitest.json` | exit0 / 181，JSON与详细日志一致；精确门禁使用真实输出 |
| `npm run build` / `npm run lint` | 均exit0；lint9条既有非阻断warning，无M06 warning |
| `python -m unittest discover -s tools/tests -v` | 正常权限exit0 / 90；其中M06门禁5项 |
| `python tools/validate_package.py` | exit0，规划产品验收仍NOT_EXECUTED |
| `npx playwright test --list` | 发现13；仅发现，不冒充执行 |
| `git diff --check`、新增文件秘密模式检查 | 无空白错误；无私钥/token模式匹配；已跟踪env仅`.env.example`，无真实凭据发布 |

本机Docker仍BLOCKED_LOCAL；真实PG/Keycloak等待六Job隔离CI。编译target17、CI目标Java21/Node24不变。Windows Wrapper本轮可用，不沿用旧轮失败描述。未合并、未部署、未恢复定时任务、未进入后续模块。

### 第一轮真实 CI（保留失败，不以部分绿代替整体）

代码`db4409e3efa4d712d8ba3e6bc67b90b9e65069f8`：[Push38936264506](https://github.com/fangzhiy/Test365Alm/actions/runs/38936264506)和[PR38936311923](https://github.com/fangzhiy/Test365Alm/actions/runs/38936311923)均failure，仅server失败，其他五Job成功。PR实际checkout `40113ac66b0fc65b5e060b14f1ea0abe23d4be9a`，不是分支head。

真实Failsafe125中failure1/error0/skipped0：`OidcCallbackSecurityIT.realOidcReleaseHttpTypedScopeSnapshotsAndOldSessionRevocation`第1072行将既有POST `/tests/{id}/revisions`预期写成201，而实际`TestCaseController.appendRevision`和既有契约为200。确认不是业务失败后，仅修正错误预期；该方法在失败点之后的失权断言本轮尚未执行，不能提前宣称通过。M06 DB12、V18→V19非空升级1均实际通过；整个OIDC21中20通过，新增HTTP不能算全部通过。后端制品 [Push12061606438](https://github.com/fangzhiy/Test365Alm/actions/runs/38936264506/artifacts/12061606438)、[PR12060728197](https://github.com/fangzhiy/Test365Alm/actions/runs/38936311923/artifacts/12060728197)保留完整失败与成功报告。

两端完整浏览器13（M06独立1）及独立项目UI1实际通过，失败/错误/跳过0；M06 ADMIN DOM创建需求/测试/追踪/Release/Cycle/范围→A→原执行PASS→B→A保持不变→真实reload→独立VIEWER读取/有效CSRF拒写。Push browser117092442151/PR117092591880，新M06分别6.1s/6.2s。只保存脱敏`release-ui-context-full.json`，没有token/Cookie/原始trace上传。

同PID4112真实停库live200/ready503→恢复200、默认回环`::ffff:127.0.0.1`、两条对照哨兵及资源ID保持、归属清理、正式V19成功后故意V20启动失败均PASS。打包故障探针Job中的`-DskipTests`仅为独立打包，不能计测试通过；真实单元/集成由server完整执行。

修复代码`fa722090586a131075f0668f253d09134bcc7e87`另外补前端精确JSON门禁：按文件和完整名称要求18组件+3公共API，缺失/失败/错误/跳过/todo/重复/计数不实均拒绝；真实181断言报告门禁exit0。新5项离线负向验证先RED缺模块，后GREEN；完整Python95退出0。CI仍六Job，web增加JSON输出和always门禁/制品，不放宽现有检查。报告格式依据[Vitest reporter文档](https://vitest.dev/guide/reporters)，实际CLI输出已验证。

## 验收映射（代码验证基准 fa72209）

[Push38936921155](https://github.com/fangzhiy/Test365Alm/actions/runs/38936921155) / [PR38936929050](https://github.com/fangzhiy/Test365Alm/actions/runs/38936929050)。Push实际checkout/构建提交`fa722090586a131075f0668f253d09134bcc7e87`；PR实际checkout/构建提交`87d4292e0b6672c3567846bcb6ec6016c8063240`，ref `refs/pull/9/merge`。后续只记录文档的交付SHA不冒充此代码SHA；最终交付CI在执行回复核对。

| 项目 | 结果与实际证据 |
| --- | --- |
| M01 | PASS：阶段A主线a2419e2、PR7/8 merged、38931708362六Job及本机smoke；顶部纠偏，旧记录保留 |
| M02 | PASS：`adminCreatesReleaseCycleAndFrozenIdempotentResultsWithAtomicEvents`确认空库19条迁移；`nonemptyV18RequirementsTestsExecutionDefectsAndTraceSurviveV19AndRemainUsable`先用runtime产生真实多修订需求/测试、追踪、FAIL运行、两版缺陷、事件/幂等，再升V19比对所有原表完整值、重复迁移0，升级后旧读/重放和新M06可用 |
| M03 | PASS：ADMIN实际服务和`realOidcReleaseHttpAdminDatesEtagsCsrfAndRoleBoundary`创建；`datesRejectInvalidCyclesAndParentShrinkWithoutSideEffects`验证真实日期、包含边界、父缩短精确422与无副作用；事件/幂等数量独立核验 |
| M04 | PASS：`typedScopeFreezesCurrentRevisionsAndRejectsForeignOrWrongTargets`及`realOidcReleaseHttpTypedScopeSnapshotsAndOldSessionRevocation`；真实HTTP加入需求/测试，同租户异项目、跨租户、错误实体拒绝；原scope revision在源追加后不漂移 |
| M05 | PASS：`twoAdminsUpdatingReleaseOrCycleHaveOneExactStaleLoser`两组真实并发独立事务，恰一成功一412/STALE_VERSION；`archivedReleaseAndClosedCycleRejectIllegalWritesAndTransitions`关闭/归档新写无副作用拒绝 |
| M06 | PASS：`snapshotsStayImmutableWhileTraceExecutionDefectAndScopeFactsChange`由无执行→FAIL/缺陷→失效追踪/复测PASS/关闭→源与范围变更，逐次比较A/B完整JSON；读取历史不重算；真实DOM流程另见浏览器报告 |
| M07 | PASS：`metricsExcludeInactiveTraceActiveRerunsAndUnrelatedRevisionPass`覆盖无关/跨项目PASS、活动rerun压制、最新不同revision、失效追踪、REOPENED；分子/分母和冻结来源证据核验，27条分页验证指标不受当前页影响 |
| M08 | PASS：`adminOnlyRuntimeRlsRejectsMembersForeignScopesAndIdentityLoss`、`runtimeCannotRewriteSnapshotsOrForgeCrossScopeRelations`证明当前运行账号非owner/superuser/BYPASSRLS、复用同一连接切范围仍隔离、writer规则；两个真实OIDC方法对原会话直接撤权/降权/停用/租户失效重验，无/me前置 |
| M09 | PASS：`sameKeyConcurrentCreatesEvaluateAndScopeHaveOneFrozenResult`真实并发同键只一份结果/事件，不同内容409；`auditAndOutboxFailuresRollbackCreateEditScopeAndEvaluateExactly`对create/update/scope/evaluate×两故障共8项，精确permission denied根因、完整表/分配器/幂等前后相同，恢复后同意图成功；runtime禁止历史snapshot更新/删除/清空 |
| M10 | PASS（代码基准）：38936921155/38936929050六Job全部completed/success。精确DB/HTTP/frontend/browser门禁与缺失/失败/错误/跳过/重复负向检查通过；最终文档交付的新Push/PR结果由执行回复核对，不用本基准覆盖新结果 |

报告层次：Surefire46；Failsafe125，其中M06 DB12+非空升级1、整个OIDC21中新增M06 HTTP2；不是21个M06矩阵。原M07 DB13、M08 DB10、M09 DB23/升级3、M04/M11 DB10、M10 DB9等全部保留执行。Vitest181（新增18组件+3API）；Python95（新增DB/browser门禁5+frontend门禁5）。所有已完成层failure/error/skipped0；成功后端制品 [Push12061428336](https://github.com/fangzhiy/Test365Alm/actions/runs/38936921155/artifacts/12061428336) / [PR12062045723](https://github.com/fangzhiy/Test365Alm/actions/runs/38936929050/artifacts/12062045723)，含XML、`release-r10-report.txt`与`checkout-sha.txt`。

实际CI命令：`./mvnw -B -ntp test`、先M09非空升级专项再`./mvnw -B -ntp -Pintegration verify "-Dbuild.commit=${GITHUB_SHA}"`及全部旧/新报告门禁；`npm ci`、lint、上述完整Vitest JSON、TypeScript/Vite；Python95/规划/P0；两故障脚本归属校验。已完成步骤均exit0。CI Java21.0.12.1/Maven Wrapper3.9.16、Node24.21.0/npm11.19.0、Python3.12.15；本机差异和实际阻塞见上，不更改全局配置。

代码基准两端浏览器最终均13/0/0/0，单worker/零retry，独立项目UI另1。Push job117094633153：独立15.3s、完整2.5m、新M06 4.6s；PR job117094660244：独立15.9s、完整2.8m、新M06 6.2s。完整输入、迁移、门禁、页面和测试已进入实际远端代码树。全部业务验收在该代码基准通过；没有将本机Docker BLOCKED_LOCAL写成通过，也不把内部自审写成外部审核。全局阻断NONE；最终交付文档提交及其CI交由回复独立核对。

## 修改文件、演示与边界

- `apps/server/.../release/`四类及V19：有限业务、typed FK/RLS/日期/历史；`ProjectService`只加M06权限。无旧正式迁移更改。
- 新两IT/规则测试、现有OIDC类新增两方法；旧Principal/需求/用例升级计数跟随正式19，旧M10升级明确target18以保持原证据含义。
- `apps/web/src/Release*`/`releasesApi*`、App入口及`e2e/releases.spec.ts`：真实工作台、轮次/草稿/恢复及DOM验证。
- `.github/workflows/ci.yml`、迁移探针、旧OIDC/browser门禁、新M06 DB/web门禁及离线测试：不减少六Job，不改变单worker/零retry。
- README/AGENTS/契约/ADR024/完整输入/状态/本记录：事实与可复验命令；不改规划范围或产品验收状态。

演示：按README现有隔离环境启动→ADMIN登录选项目→创建需求/手工用例/typed追踪→发布工作台建Release与包含日期的Cycle→选择现有需求/测试加入范围→查看冻结revision→生成A（未执行0/计划数）→原执行工作台建测试集/固定实例/运行，DOM保存PASS并完成→再次evaluate B→B新事实、A完整旧事实保持→真实reload重选项目读历史。独立VIEWER进入相同发布能读周期/范围/快照，没有写入口且有效CSRF直接POST403。412需显式采用/放弃基础版本，未知操作重试原键，确认写后仅GET恢复。

限制：仅本轮最小M06，不含完整Gantt/排期/依赖网络/资产/审批/通知/SLA/异步报表/AI/外部同步/M12。容量上限与v1统计口径固定于契约，不声称完整ALM兼容或生产性能/全量安全审计。M01/G0真实旧ALM授权/样本等缺失输入继续保留，但不阻塞本切片。原32模块、WBS和产品AC状态未提升；9条既有lint warning和Action运行时弃用告警登记后续，不扩成依赖升级。新增文件/修改文件无删除；全文远端回读与原输入相等（仅末尾换行归一）。
