# D 用例复核离线检查点

依赖总管确认的已验收 B 基线 `23d138cda8fd6d34ec598064778b42b121175109`，分支 `feat-review`。仅本地提交；没有 push、PR 或 dev 合并。正式 V25、故障探针 V26；V24 仍由 C 持有。本文件记录未完成的 D1/D2，不能据此勾选业务验收。

## 已实现

- `ef0f1da`：复核契约、严格输入及状态机纯逻辑回归。
- `a0c76ab`：确切不可变修订的追加复核事件、数据库内容摘要、RLS、事务提交完整性约束、服务及 API。
- `3ddca61`：真实 API 复核面板、只读历史、共享版本联动、未知结果保留原意图并锁住相关内容写入。
- `b4d0a27`：数据库/真实 OIDC/非空升级/真实 Keycloak 页面生命周期回归源码；当前修订与历史修订标签、响应范围绑定。
- `8ab81c5`：指定身份按 UUID 值比较；冻结业务字段可合法使用大写 UUID，摘要仍保留原冻结内容。
- `a93ba1a`：15 项确切 D 数据库/升级/HTTP 名称门禁及离线反例、OIDC 闭集27项、浏览器新增必需流程、正式 V25→故障 V26 探针和 CI 制品。

`TestReviewService.act` 每次动作/重放均先要求有效项目 writer、读取确切修订并重新核验冻结 reviewer 的 `app_has_active_project_writer`。批准/拒绝必须是指定身份，admin 不绕过，不增加禁止自复核。用例共享 rowVersion 与内容编辑竞争，审计/Outbox/事件/版本/幂等同事务。修改内容追加新修订，新版 DRAFT，旧批准不改写。

`V25__test_case_revision_review.sql` 使用只读内容摘要函数，包含确切身份/修订号、标题、描述、前置条件、全部冻结字段和有序步骤。运行身份仅 SELECT/INSERT 复核历史；直接伪造身份、摘要、状态或缺少匹配审计/Outbox/冻结响应的提交受 RLS/触发器约束。旧 V1—V23 未修改。

## 实际验证

- Java21：`mvn -B -ntp test`，91 单元、零失败/错误/跳过；新增所有 IT 源码已编译。使用 `D:/code/ProOrca/Wetest365ALM/.tools/jdk-21`，编译配置仍为仓库既有 release17，未擅改目标。
- 前端：`npm ci` 后完整 Vitest 270项通过；最后解析器范围加固后相关36项再次通过，TypeScript/Vite build 通过。lint 退出0，未新增 warning；既有 App/Requirements/TestCases/TestExecution warnings 保留。
- Python：完整工具回归173项通过，包括新复核门禁6项。门禁测试逐项删除/替换/重复、计数/类伪造、suite绿色但case失败/错误/跳过等都失败关闭。
- `npx playwright test --list`：实际发现15项，包含新的 `real Keycloak UI designated reviewer rejects approves and new revision requires fresh review`；这只是发现清单，不是浏览器业务通过。
- `trail check`：退出0、无冲突；提示比较分支默认基线的diff超400KB，仅检查前400KB及既有worth-a-look项。`git diff --check`通过，`trail build`已刷新。

脱敏离线本机结果在忽略目录 `local-evidence/review/`。本分支源码库存：B原175个 IT，D新增15个，现190个 IT；没有删旧方法。当前迁移实际24个文件（V1—V23加V25），不是maxVersion=25。

## 未执行、下一步

总管明确 C 持有下一重型 Docker 时段，D 在 C 验收前只做离线工作。此检查点没有启动 Docker、产品服务、Keycloak 或真实浏览器；没有触碰W1或5173/8080。D的真实数据库/HTTP/非空升级、完整集成、独立故障探针、真实浏览器均 NOT_RUN。

1. 先由总管发送已验收 C 的确切 SHA；在本功能分支合流该依赖，保留双方共享测试、UI与设施，不能用单方整文件覆盖。
2. C 合流后改 `TestReviewMigrationUpgradeIT` 起点为非空 V24→V25（当前离线源码仍为V23→V25、1项正式迁移）；逐个适配已有 latest 升级计数和旧快照夹具。当前未调整 legacy latest 迁移断言，其真实执行会失败关闭，不能直接运行并宣称已全绿。
3. 根据组合源码逐名核对全部IT、单元和浏览器清单；`tools/linux_test_inside.py` 仍是B的89/175及8专项，故意未擅改C的共享runner。组合后应保留C的51专项，并加D的15项及相应回归，在独立设施提交中交总管审查；不得任意selector/count、重试、skip或删断言。C93单元/187IT加D预期95/202仅为待核对预估。
4. `tools/local_browser_runner.py` 仍保留已批准的三流程名单；组合后在独立设施提交中增加D流程并测试严格名单/零重试，再交总管确认实际四流程及资源放行。原完整15项浏览器全集门槛保留。
5. 取得串行时段后，用固定提交归属资源执行 scan→unit→focused→唯一full；零F/E/S且精确清理后执行含D的真实Keycloak浏览器，保留真实失败与清理状态。所有源码修复必须新提交并重新验证对应固定SHA。
6. 真实验证后总管复核，才可确认D1/D2。本任务不自行push/PR/merge dev，也不勾选任务清单或宣称签署/报告已实现。

新增经验保存于 `.trail/learnings/2026-11-10-review-approval-belongs-to-frozen-revisions.md`；其余 `.claude/settings.json`、`.trail/hook.cjs` 是开工前自动配置修改，保留且不入功能提交。
