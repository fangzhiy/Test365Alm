# 交接：feat-business-fields（B／路线图1c）

最终业务源码：`da9906c6967edd53bfde5808eb405a47b7106571`；浏览器选择器修复源码：`b5cc1b2a7e4e6ded36c9ce83551bf6296132aa94`（仅两处测试选择器，生产代码相同）。基线61711294ff10e16b8df29418badaa8b455bc80ea，已合dev@8bd7408（merge ef2e083）。最终交接／learning提交不改变被测源码；未push／PR／merge dev，未修改其他worktree。两项自动修改 `.claude/settings.json`、`.trail/hook.cjs` 保留且不提交。

## 契约与D入口

完整契约 `contracts/business-fields.md`。扩展三实体既有创建／修订API，真实持久化于test_revision／defect_revision／requirement_revision.business_fields JSONB，不用metadata validate端点冒充保存。冻结kind／fieldId／revision／definition／value；新建省略为空，编辑省略继承，显式{}清空，显式null拒绝；未知键／属性、错误类型、边界及跨项目定义均拒绝。SYSTEM默认版本可直接使用，CUSTOM同键复用FieldDefinitionService／FieldRules按确切定义修订校验。字段改变创建内容新修订，元数据变更不回填历史；封存修订／旧manifest不变。强If-Match、持久幂等及重放重新授权、RLS、业务／audit／Outbox同事务保持；省略新字段保留旧请求哈希编码。

| 实体 | 字段 | 原生映射 |
|---|---|---|
| 用例 | test_purpose／reviewer | description仍为一般描述，目的独立 |
| 缺陷 | occurrence_department／occurrence_location／reviewer | description、来源、状态保留原义 |
| 需求 | equipment_system_id／owning_department | title／body／priority保留原义 |

D取确切test_revision.id的 `test_revision.business_fields.reviewer.value` 本地主体UUID，在批准事务的项目权限边界内再次调用既有SQL **app_has_active_project_writer(tenant_id,project_id,principal_id)**。仅有效PROJECT_ADMIN／PROJECT_MEMBER可被指定，viewer只读，租户角色不能代替项目资格。normalize／V23完整性／候选目录统一此资格，覆盖principal／tenant_member／project_member停用、撤权、有效期。不信任显示名，不用当前实体字段替代指定修订；本分支未实现复核状态机。

只读 `GET /api/v1/projects/{projectId}/reviewer-candidates`：search字面子串最多200字符，limit1..100默认25，UUID keyset cursor，可选principalId精确解析已存身份。ReviewerService先CoreCommands.scope(read)，V23窄SECURITY DEFINER app_business_reviewers再次验证actor／scope，只返回有效同项目writer的principalId／displayName；未开放管理员成员管理权限。UI按姓名搜索／分页选择，保存稳定UUID；当前／修订／HistoryPanel从授权目录显示“姓名（当前成员姓名）”，失效或不可读明确显示不可用，不暴露UUID或伪造历史姓名。

## 合流点与迁移

保留FolderTree／HistoryPanel接入；没有修改execution模块或TestExecutionPanel。HistoryService最小增加业务字段值／冻结定义差异，HistoryPanel复用readonly ReviewerPicker；合流保留既有刷新逻辑。三实体API／表单／历史、冲突草稿及组件回归已接通。

正式仅V23，旧V1—V22不改，V24留C，故意失败探针V25；本分支正式迁移数量23，不等于最大探针号25。V25独立启动故障脚本本轮未运行。

197ac06冻结test-only LegacyRequirementService／LegacyTestCaseService／LegacyDefectService／LegacyDefectRules／LegacyContentServices，原SQL／行为来源61711294ff10e16b8df29418badaa8b455bc80ea，说明见 `apps/server/src/test/java/com/test365alm/server/businessfields/legacy-content-fixtures.md`。保持真实restricted runtime、原授权／RLS、事务／audit／Outbox／幂等，无生产组件注解；反射拒绝未显式覆盖的新public方法。保留旧target内容／幂等／manifest快照断言；升latest后调用当前正式服务，不用owner造业务或在生产动态探测schema。

共享AssetMigrationUpgradeIT／DefectMigrationUpgradeIT／ReleaseMigrationUpgradeIT：B负责旧内容与latest切换，C在自己分支负责旧ExecutionService／run_step_id资产兼容；总管合流保留双方。C的V24和新增方法未包含本次89／175；组合预期93／187须重新逐名核对并验证，不沿用本报告。

## 实际检查

离线：Java21全单元89；前端262（27文件），lint exit0（9个既有警告），build成功；Python165，设施守卫13；trail build／check执行，仅非阻断提示。Maven显式使用D:/code/ProOrca/Wetest365ALM/.tools/jdk-21，未用系统17。

总管受限例外批准独立设施提交，仅更新精确计数并增加固定focused；README逐名记录，任何非0／缺失／重复／failure／error／skip阻止后续，无retry／降低门槛。focused固定命令为 `mvn -B -ntp -Pintegration verify -Dit.test=BusinessFieldsDatabaseIT,BusinessFieldsMigrationUpgradeIT`。

最终Linux run **16077b4faf6c450287f50b31103f07d2**，源码 **da9906c6967edd53bfde5808eb405a47b7106571**，证据 `local-evidence/linux-tests/16077b4faf6c450287f50b31103f07d2/`：

| 阶段 | 单元 | 集成 | 实际结果 |
|---|---:|---:|---|
| scan | 89 | 1真实ClamAV/EICAR | exit0，零F/E/S |
| unit | 89 | — | exit0，零F/E/S |
| focused | 89 | 8 | exit0，零F/E/S |
| integration | 89 | 175 | exit0，零F/E/S |

逐项方法身份核对无重复。focused为BusinessFieldsDatabaseIT七方法：fieldsPersistOnExactRevisionReplayAndHistory、runtimeSqlCannotRewriteSealedFieldsOrInsertForeignReviewerAndViewerCannotWrite、outboxFailureRollsBackBusinessFieldsAndIdempotency、reviewerRejectsForeignDisabledExpiredAndRevokedIdentitiesAndReplayReauthorizes、customDefinitionRevisionsRemainFrozenAndCrossProjectIsRejected、requirementAndDefectFieldsPersistAndStatusCopiesFrozenValues、reviewerDirectoryIsReadableByMembersBoundedSearchableAndRejectsForeignOrInactiveScope；加BusinessFieldsMigrationUpgradeIT.nonemptyV22UpgradePreservesAllHistoricalContentAndRuntimeCannotRewriteIt。

Docker context default，Engine a6f010ac-61a2-4544-b133-c2b0d92d11d3，runner image sha256:3b07f5c6646200ffcde3f1f9cb55243546e5fd01de739235a420cd96184fd0b1；run.json保存37个精确资源身份，exit_code=0、cleanup=true。保留身份／Engine／精确ID守卫，只导出白名单脱敏方法／计数／身份，不导出Linux原始日志／秘密／EICAR。

最终真实Keycloak浏览器源码 **b5cc1b2a7e4e6ded36c9ce83551bf6296132aa94**（与后端已验证生产源码完全相同，仅两处选择器修复），run **r03-local-41223325ed4b445fa8afd6da363faf95**；证据 `local-evidence/local-browser/41223325ed4b445fa8afd6da363faf95/` 的results.json／run.json／manifest.json和repo/local-evidence/r03三条脱敏timeline。三个精确方法均passed，expected=passed、retry=0，workers=1、retries=0，无skip：

- `real Keycloak UI manual test case flow creates steps, edits order, and keeps viewer read-only`
- `real Keycloak UI manual execution creates, resumes, finishes and reruns a two-step run`
- `real Keycloak UI member creates defect from FAIL retests PASS closes and preserves history`

实际通过按姓名选择有资格R03 Member、用例目的／复核人保存与页面重载、修订历史与审计姓名；缺陷部门／地点／复核人保存重读历史；需求编号／部门保存重载。context desktop-linux，Engine与Linux验证相同；项目test365alm-r03-local-41223325ed4b445fa8afd6da363faf95，postgres精确ID 80d67d52ec28341645bccd4e4994a5fd7232f830f5f281894cd4d429e25fff03，keycloak精确ID f08855fdd7212e09b63f8e6864a7f65297eee1d4a99c50f3c55b1ce8417c9507。run.json确认exit_code=0、cleanup=true、processes_stopped=true，已向总管释放串行Docker时段。使用隔离随机端口／随机秘密，未使用日常.env、5173／8080、W1演示、假GITHUB变量或mock扫描；未改Defender。

## 失败证据与修复

1. 52e7735／run8d25f317e41b4df08e440eca8e6cb715：scan89/1、unit89通过，focused7P/1E，full未启动，cleanup=true。测试先撤权reviewer后正向创建仍指定它，服务正确拒绝；7433ef2改用仍有效f.admin，末尾仅撤权动作主体f.member；保留指定人资格撤权与重放主体撤权两条边界。
2. 7433ef2／runc50d90143f4f46b39f8e68ba4f1817e7：scan／unit／focused通过，full175为173P/2F/0E/0S，cleanup=true。仅AssetMigrationUpgradeIT／ReleaseMigrationUpgradeIT旧key冻结DTO相等失败，旧target／latest内容快照通过。Jackson3旧JSON null往返为NullNode，与Java null不等；e08140f仅四类响应DTO边界NullNode→null，保留历史null与旧key相等断言、输入显式null拒绝。四类旧／空／非空响应往返与独立trail learning已提交。
3. 非空往返单元RED另定位新建revision的LongNode与JSONB读回IntNode不等；7749951按持久JSON形式规范化，不改值／历史，DB补无变更保存不建修订断言。da9906c独立修复HistoryPanel姓名渲染，组件RED→GREEN并补浏览器重读。最终使用新源码／新run，未覆盖旧失败报告。
4. da9906c浏览器run bb35d43bc533419a9fc4ee1f9c07db1e：缺陷／执行通过，用例流程在选择复核人时失败，workers1／retries0，cleanup=true、processes_stopped=true。嵌套select的option文字影响Playwright exact getByLabel文本匹配；独立真实Edge最小DOM复现exactLabel=0、partialLabel=1、exactRole=1。b5cc1b2仅改创建／重载为精确combobox可访问名称查询，保留全部业务断言；lint和单方法发现通过，无需重跑未变的后端。独立trail learning记录差异，不增加超时／retry或删断言。

## 未执行与剩余

未实现复核／签署／报告，未验证C字段及组合93／187，未单独运行V25启动故障探针，未跑完整14条旧浏览器全集；不等同整条路线图1c或OQ验收。总管需合流共享升级夹具／V24／计数，D按上述稳定身份与确切修订资格契约实现动作。B不执行push／PR／merge dev。
