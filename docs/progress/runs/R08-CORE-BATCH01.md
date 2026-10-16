# R08-CORE-BATCH01 执行记录

日期：2026-10-12。输入全文：[原文](../inputs/R08-CORE-BATCH01.md)。仓库：https://github.com/fangzhiy/Test365Alm 。本轮分为先合并既有 PR #6、再开发独立 M04/M11 切片；仅授权合并 PR #6，新 [PR #7](https://github.com/fangzhiy/Test365Alm/pull/7) 保持 Open，交由独立审核。

## 1. 起点、主线身份与阶段 A

实际 Git 仓库位于 `jihua/Test365Alm_ProjectPackage/Test365Alm`，不是外层规划目录。本地起始历史与此前 Git Data API 发布链不同；开始时保留原目录和已有内容，读取真实 GitHub 对象后从已验证主线创建新分支。本轮正常 Git 提交/推送，本地与远端 R08 SHA 相同，没有 reset、强推、重写历史或覆盖并发远端修改。

先读取 AGENTS、README、研发版 Word、M04/M11 模块、WBS/sprints/验收清单、契约、ADR 和 R02—R07 记录。原 Word、规划任务和完整模块验收状态不改。

阶段 A 开始时默认分支 `chore/r01-status-001` 为 `1a202b00f793ab6f8c7a354aac619475de48e0c9`；PR #6 head `715a223a92b810c54450c36b5fe6c53201dec57e`，base 为该默认分支。GitHub reviews/comments/rulesets 无新增阻断，分支未保护，Mergeable=true/clean。

正确 merge-ref：`583e056914f1edbe44f1b87f912eab269775a863`，父提交依次为上述默认分支和 PR head，tree `0dba2efc75ec90acbe8a57970c260a3a9f7d97dc`。受控 Close 05:23:34Z → Reopen 05:23:38Z 仅一次，没有改 head/base/业务代码。新 [PR CI 38207737402](https://github.com/fangzhiy/Test365Alm/actions/runs/38207737402) 六 Job success，实际 checkout 为正确 merge-ref；不是历史旧候选 `7a53095...`。

以 expected head 执行授权 Merge Commit，实际合并 SHA `1e49617f283ffa54673d590a1aba3e72fcfbe14e`。父提交依次为 `1a202b00...`、`715a223...`；tree 与 head/正确候选相同。PR #6 head 已为默认分支祖先；合并后默认分支 SHA 即该 merge SHA。[主线 Push 38208110087](https://github.com/fangzhiy/Test365Alm/actions/runs/38208110087) 实际 checkout 为该 merge SHA，六 Job 全部 success，V1—V16、V13/V14 非空升级和原完整回归通过。

PR #1～#6 均 merged=true，最终实际 merge SHA：

| PR | Merge Commit |
| --- | --- |
| #1 | 632aac20cec5d05e16b273e6df9bc5931d9b97e3 |
| #2 | 86c422c0538e1f0a685e1c616f424518c4e604fe |
| #3 | 7ce2cdd22f442168347fde64d0f91f5729d212d2 |
| #4 | e23653460be629dc994e2af865d2115a53e57eef |
| #5 | 1a202b00f793ab6f8c7a354aac619475de48e0c9 |
| #6 | 1e49617f283ffa54673d590a1aba3e72fcfbe14e |

A01/A02/A03：PASS。只有新主线 CI 成功后才创建 `feat/r08-m04-m11-001`，新 PR base 为 `chore/r01-status-001`。

## 2. 已实施的有限范围

M04-T01 / M04-T02 共享验证基础：项目 CUSTOM 定义与只读 SYSTEM 保留目录；TEXT/NUMBER/ENUM、必填/默认值/长度/精度/选项；稳定 UUID/key/type 和追加不可变定义修订；指定修订共享值验证；管理员管理页面和服务端验证入口。NUMBER 使用精确十进制字符串，不使用浮点隐式转换。旧定义不重写；本轮没有业务实体扩展值存储或历史回填。

M11-T01 当前实体约束 / M11-T02 需求→用例子集：实际类型化端点、同范围复合 FK、冻结双方修订、稳定关系 ID/版本、明确失效历史、有效关系去重、持久幂等、有限 keyset 页面；需求详情内可选择真实用例、创建/查询/失效关系。

覆盖是授权单需求的同步数据库汇总，规则 `CURRENT_BOUND_REVISIONS_LATEST_FINISHED_NO_ACTIVE`：关联分母不等于 PASS；绑定修订仍为双方当前修订、确切用例修订最新已完成尝试 PASS 且无活动重跑，才增加 passedTests。真实未执行为 0，真实执行 PASS 为 1；活动重跑不能沿用旧 PASS。不是异步 coverage_projection，不声称 suspect、水位或完整影响传播。

字段写入仅有效 PROJECT_ADMIN；MEMBER/VIEWER 可读/验证。关系写入 MEMBER/ADMIN，VIEWER 只读。复用真实 OIDC、ProjectService、事务本地 tenant/project/principal、受限 runtime/RLS、CSRF、强 ETag、重新授权后的冻结幂等重放。业务、不可变修订、审计、Outbox 和幂等同事务；owner 只用于迁移、隔离夹具和独立核验。

## 3. 文件与提交

- `f0b86362452ff9e8e3d7d8a4cfc3b4bf61dd9f7c`：首个实现、输入原文、契约、V17、API/UI/测试和门禁。
- `decfb104732196cf1d889aaf8a601c29624558ff`：旧 OIDC 精确名称门禁补入新增两例；项目浏览器冲突断言改为真实 PATCH 409/STALE_VERSION + 本面板通知，避免 SYSTEM row_version/版本说明误匹配；可选 null 默认值的公共前端响应解析回归。
- `f4eb5df1ec4cc9ad808934ee80c470c251d3941e`：额外明确同租户不同项目、同一 runtime 连接 own→sibling→own 范围隔离，以及精确测试名门禁。最终代码验证提交；不修改生产行为。

主要文件分组：

- `apps/server/.../core/CoreCommands.java`：共用授权/上下文、幂等/摘要、版本及审计 Outbox 命令边界。
- `apps/server/.../metadata/`、`.../trace/`：字段规则、字段定义和 typed 关系服务及 HTTP Controller。
- `apps/server/src/main/resources/db/migration/V17__field_metadata_and_requirement_test_links.sql`：字段/修订、关系/修订、共用命令幂等/Outbox、复合 FK、RLS/GRANT；V1—V16 原样保留。
- `apps/web/src/{coreApi,useCoreRequest,FieldMetadataPanel,TraceLinksPanel}*`：真实 API 解析、范围/请求代次/8 秒超时、字段和需求追踪 UI；App/RequirementsPanel 最小接线。
- `CoreMetadataTraceDatabaseIT`、`FieldRulesTest`、`OidcCallbackSecurityIT`、`ManualExecutionMigrationUpgradeIT` 和既有升级数量断言：真实隔离 PostgreSQL/runtime/HTTP/非空升级。
- `apps/web/e2e/core-metadata-trace.spec.ts`：一条新增真实 Keycloak 组合流程；旧项目冲突断言精确修正，不删原十例。
- `tools/verify_r08_core_report.py`、相关工具测试/旧 OIDC 与浏览器门禁、`.github/workflows/ci.yml`：新增精确名称证据，保留六 Job；正式迁移 V17 后专用失败探针 V18。
- 两份新契约、ADR 022、README、AGENTS、输入、执行记录及当前状态：边界、使用方法和实际证据。完整变更清单以 `git diff --name-status 1e49617... HEAD` 为准。

## 4. 实际命令、环境与证据

本机 Windows：Java 17.0.2、系统 Maven 3.9.14、Wrapper Maven 3.9.16、Node 26.0.0/npm 11.12.1、Python 3.12.8。CI Ubuntu：Java 21.0.12.1、Node 24.21.0、Python 3.12、Wrapper 3.9.16；编译 target 17 沿用 ADR 006，不改系统默认工具或整体依赖。PostgreSQL 17.11 使用工程固定 digest；测试动态专属容器，不读取开发 `.env`。

| 命令（对应目录） | 退出码/结果 | 对应版本与证据 |
| --- | --- | --- |
| `mvn -B -ntp -Pintegration verify -Dbuild.commit=f0b8636...`（server） | 0；41 单元、96 集成 | 干净 f0b8636；target/surefire-reports、failsafe-reports |
| `.\mvnw.cmd -version`、`.\mvnw.cmd -B -ntp test`（server） | 分别 0、0；41 单元 | 干净 decfb10；Wrapper 与系统 Maven 独立执行 |
| `mvn -B -ntp -Pintegration verify '-Dit.test=CoreMetadataTraceDatabaseIT'` | 0；41 单元、9 专项集成 | f4eb5df 同内容提交前；真实临时 PostgreSQL |
| `mvn -B -ntp -Pintegration verify '-Dbuild.commit=f4eb5df1ec4cc9ad808934ee80c470c251d3941e'` | 0；41 单元、97 集成；失败/错误/跳过全0 | 开始时干净最终代码提交；执行期间仅准备后续文档，无代码/配置改动；全部回归 |
| `npm ci`、`npm run lint`、`npm run test:run`、`npm run build`（web） | 均 0；145 Vitest，TypeScript/build 成功 | decfb10 同内容；最新代码只新增 DB 测试/门禁，CI 再完整运行 |
| `python -m unittest discover -s tools/tests -v`（根目录） | 0；80；原75+5 | f4eb5df 同内容提交前；合成负向打印 FAIL 是被测试的预期拒绝，unittest 全通过 |
| `python tools/validate_package.py`、`python tools/p0_health_check.py` | 0、0；规划包完整，缺失输入继续保留 | 代码 CI 干净检出；不是产品验收 |
| 精确名称 R03/R04/R05/R06/R07/R08 报告门禁 | 全部要求真实报告且零失败/错误/跳过 | CI server / oidc-browser，制品见运行页 |

沙箱内曾发生 Mockito named-pipe attach/临时目录权限和 npm EPERM 失败，不混入成功退出码；使用已授权沙箱外测试重新执行。当前本机真实 Docker/Testcontainers 已可用，不能沿用历史 JNA BLOCKED 作为本轮结果。本机没有另行搭建 Keycloak 浏览器环境，真实 UI 证明来自本轮隔离 Ubuntu CI；没有冒称 DevTools MCP 已执行。

首次代码 CI [Push 38211374411](https://github.com/fangzhiy/Test365Alm/actions/runs/38211374411) / [PR 38211403868](https://github.com/fangzhiy/Test365Alm/actions/runs/38211403868) 均 failure。PR checkout `d1000a76eafc367394c490b5558d64f4dc32a33c`；失败原因是旧 OIDC 精确名称集合未接纳新增两例，以及旧项目浏览器宽泛 version 文本匹配被新 SYSTEM 目录命中。业务单元/集成/前端本身通过，但不据此将整轮记 PASS。decfb10 修复后 [Push 38212028698](https://github.com/fangzhiy/Test365Alm/actions/runs/38212028698) / [PR 38212034656](https://github.com/fangzhiy/Test365Alm/actions/runs/38212034656) 六 Job 均 success；PR 实际 checkout `d49f0b4c750669fa9d143094e9f52ff9f454134b`。

最终代码 CI：[Push 38212925326](https://github.com/fangzhiy/Test365Alm/actions/runs/38212925326)、[PR 38212928874](https://github.com/fangzhiy/Test365Alm/actions/runs/38212928874) 均 completed/success，六 Job 全部 success。Push checkout 为 `f4eb5df1ec4cc9ad808934ee80c470c251d3941e`；PR 实际 checkout `57397f208f0f80fa45d1351888b4f032e44a1d66`，ref `refs/pull/7/merge`，两个父提交为 `1e49617...`、`f4eb5df...`，tree `de676aca203bd6e69d559357e020b3853a1b8d4b` 与代码 head 相同。读取日志而非把 Actions head_sha 当实际 checkout；PR/browser 使用各自对应构建 SHA。

本次两个运行均保留六 Job：planning-tools、web、server、migration-failure、readiness-recovery、oidc-browser。实际报告：41 单元、97 集成、145 Vitest、80 Python、11 Playwright，均零失败/错误/跳过；浏览器单 worker、零 retries，独立项目 UI 对照另1。迁移失败检查确认正式 V17 成功后故意 V18 退出1；停库恢复 live=200、ready=503→200 且同后端 PID，回环监听 `::ffff:127.0.0.1`，预存对照的两条哨兵与资源 ID 未变，清理只触及本轮资源。

代码 PR 制品经 API 确认均存在且未过期：backend-evidence 11729577337、frontend-evidence 11730062637、planning-evidence 11729736923、r03-oidc-browser-evidence 11730111842、readiness-recovery-evidence 11729553658、migration-failure-evidence 11729463919。最终文档交付 SHA/新 CI 只在交付回复报告，避免自引用提交循环；文档提交不会改变业务代码，但仍需完整最终 CI。

报告位置：server 制品的 Surefire/Failsafe XML、`core-r08-report.txt` 与既有专项门禁；frontend 的 Vitest/安装/lint/build；planning 的含 stderr unittest 和规划报告；browser 的完整/独立项目 UI JUnit、脱敏事件上下文；readiness/migration 的配置隔离/资源 manifest、监听/PID/构建身份、启动日志与结果。每次运行六项制品，不提交原始秘密或庞大日志。

## 5. 验收映射与有限结论

| 范围 | 具体证据 |
| --- | --- |
| B01 分支基础 | 从实际已验证主线 1e49617 创建新分支，PR #7 base 为默认分支，旧 PR 不追加功能 |
| B02 M04 | `FieldRulesTest` 3；Core DB 的定义/并发/回滚/权限/分页/同租户连接复用；HTTP `realOidcFieldMetadataHttpAdminViewerCsrfTypesVersionsAndScope`；FieldMetadataPanel 5 组件例；新增真实 UI 场景 |
| B03 M11 | Core DB 的 typed 端点/冻结重放/非法已删除需求/跨范围/并发去重/回滚/真实 PASS/分页；HTTP `realOidcTraceHttpTypedEndpointsViewerCsrfRevokeAndNoIllegalEffects`；TraceLinksPanel 2 例；真实 UI MEMBER 关联→执行 PASS |
| B04 UI | `real Keycloak UI field metadata validates and member trace coverage changes only after PASS`；管理员字段→合法/非法服务端验证；普通 MEMBER→需求/用例→关系→PASS 0/1→实际执行→1/1；VIEWER 无写入口且有效 CSRF 直接写拒绝；关系失效→0/0 |
| 迁移/回归 | `v16NonEmptyExecutionDatabaseUpgradesToV17WithoutLosingHistoryOrReplay`；保留 V13/V14→V16 两例；V16 非空快照 4 run/4 attempt/8 step/13 event/13 Outbox/13 audit/1 idem 完整保留，runtime 历史/继续/重放仍可用 |

最新代码各项 B01/B02/B03/B04 及共用安全/升级/完整回归：PASS（仅上述明确子范围）。最终核定 Surefire41（原38+3）、Failsafe97（原85+12，其中 core9、HTTP2、V16升级1）、Vitest145（原135+10）、Python80（原75+5）、Playwright11（原10+1）。不能把 OIDC 类17例全算新模块 HTTP，其新增为2；新 Keycloak 组合业务例仅1，完整11之外独立项目 UI 对照1。最终交付 CI 仍在回复另行核对，不把代码 CI 等同文档交付 CI。

提交检查：`git diff --check` 为0；规划/Word和旧正式迁移未改；新增文件/变更清单已核对，不含 .env、realm、私钥、token 或客户数据。秘密模式扫描只有既有发布工具的匹配规则命中，复核非秘密；隔离测试使用合成账号/一次性数据。此有限扫描不是完整供应链/渗透审计。

真实 OIDC HTTP 测试保留实际授权码/回调/JWKS/PKCE/过滤器链和同客户端会话，业务使用 runtime，不使用 @WithMockUser 或 owner 替代。报告门禁离线正反测试覆盖缺名、重复、错误/失败/跳过、报告计数/类名不一致、缺文件和删掉新增浏览器例。

## 6. 未交付、风险及停止点

- 完整 M04 动态表单/高级查询/业务扩展值/导入同步脚本入口/批量回填 NOT_RUN；仅共享验证基础已交付。
- 完整 M11 实体注册、运行→缺陷、suspect/影响传播/异步重建 watermark NOT_RUN；本轮同步单需求汇总不代替它们。用例尚无归档/删除 API，不能宣称已验证不存在的能力；已删除需求、未知/跨范围端点拒绝已测试。
- M01/G0 的旧 ALM 版本、范围和授权样本等输入仍待补充；既有 P0 输入缺失、完整安全/性能/兼容验收不自动完成。原32模块范围、WBS PLANNED、AC NOT_RUN 均保留。
- 已有 lint/deprecation 普通警告登记后续，不借机升级依赖。项目级事务锁是当前有限命令并发策略，未做大规模吞吐验收。
- 下一批真实依赖：先独立审核此 PR；后续字段业务值接入需冻结实体字段绑定/修订语义，导入同步共享校验需实际入口；完整追踪需 M10 缺陷身份与权限接口，以及 suspect/投影重建一致性设计和证据。未经新任务不开发。
- 新 PR #7 不合并、不部署、不恢复定时任务；没有修改仓库默认分支、保护或可见性。最终交付 CI/远端回读在回复收口，不把自述写成外部审核通过。
