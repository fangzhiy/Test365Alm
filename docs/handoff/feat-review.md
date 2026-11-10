# D 用例复核离线检查点

## 最新组合检查点（2026-11-11，优先于下文历史）

已按总管正式续派在原 worktree/分支合流确切已验收 B+C `1b5ee2353f4b36ababb835dce2ababd1b98c0b9c`，合流提交 `7d84d59`；原 D `84eb68b` 与两份自动 dirty 均保留。未启动 Docker、产品服务、真实浏览器；未触碰 W1、日常 .env、Defender 或禁止端口。D1/D2 仍待真实验收，不能 worker_done。

- `e9e83d3`：D 非空 V24→V25 升级先封存合法旧用例/步骤/指定人，快照全部既有表、行和列，再断言仅执行1份V25且重复0份；正式runtime提交/批准/新修订与旧批准保留源码不变。旧 latest 断言按实际25份文件更新；旧目标检查点不改。B/C全部旧内容/执行夹具（包括旧target DefectEvidence绑定）、三表投影、未知执行时间、hash及冻结重放断言保留，fixture目录与已验收C逐字一致。V1—V24与C基线逐字一致。
- `e886064` 为独立设施提交：精确库存 `tools/linux-test/combined-methods.json` 固定95单元/31类、202集成/27类；相对C删除0，增加D2单元/15集成。源码注解逐方法比较及实际JDK21报告95身份匹配。固定51（C43+B8）+D15=66，D方法名在 `tools/verify_test_review_report.py:REQUIRED` 与 runner REVIEW_METHODS 双向相等回归中固定；完整202也逐名核验。任意selector/count仍拒绝。
- 固定 scan95/1→unit95→focused95/66→唯一full95/202，任何非零、缺名/替换/重复、case或aggregate F/E/S立即停止，scan只接纳真实clean/EICAR原方法；没有retry/skip兼容路径。计数是源码库存与离线门禁，尚非真实集成绿色。
- local runner保留原用例/执行/缺陷三条全流程并加入确切 `real Keycloak UI designated reviewer rejects approves and new revision requires fresh review`，必须按既定计划显式 `--with-assets` 运行。固定4条、1worker、0retry，guard新增逐条缺失/替换/重复/重放及状态负例；reporter回归保留全部真实身份和实际失败/跳过/retry状态，不把结果归一成绿色。

实际离线结果（忽略目录 `local-evidence/review/`）：

| 命令/核对 | 实际结果 |
| --- | --- |
| JDK21 `mvn -B -ntp test` | 95单元，0F/E/S，实际报告逐名匹配95库存 |
| 最后升级快照增强后 `mvn -B -ntp test-compile` | 成功；未重复冒称IT执行 |
| 完整Vitest JSON | 288通过/31文件，0失败/跳过；含新增第四流程reporter回归 |
| 原M06/M05前端名称门禁 | 对同一288 JSON实际通过 |
| `npm run lint` / `npm run build` | exit0；既有9 warning保留，无新warning |
| 完整Python unittest discover | 198通过；其中设施相关36项先RED后GREEN |
| `npx playwright test --list` | 15项/7文件发现；不是实际浏览器通过 |
| `git diff --check` | 通过 |

本轮离线失败保留：新增66/4条门禁先在旧设施失败（5F2E），随后适配通过；首次合流冲突脚本错误截断4个设施文件，被完整Python立即发现（3F1E/186），已从双方原SHA重新逐块三方恢复，并修正合流提交。修复后四文件相对C仅保留D既有门禁/迁移版本差异，全部198通过；旧失败日志不覆盖。该故障没有触及产品或真实资源。

Trail：合流检查因400KB上限只检查部分diff，3处词语误报与总管C审查一致（folder_placement与冻结revision、pending与历史刷新）；合流提交已写明理由。设施检查2处词语误报为固定真实扫描方法名/固定专项selector；实际保留唯一真实clean/EICAR，且完整库存包括两个无runtime角色升级测试，V25授权已有角色存在条件，不增加角色夹具或降低断言；独立设施提交已说明。没有删改旧learning，不能声称全diff静态审查无局限。

下一步由总管审查本源与独立设施diff并发明确串行窗口，然后在确切提交上执行固定后端四阶段、精确清理，再运行显式with-assets四条真实浏览器及清理核验。D数据库/HTTP/非空升级、真实clean/EICAR/完整集成、独立故障探针、真实浏览器仍NOT_RUN；完整15条浏览器全集也NOT_RUN。不得把本检查点作为D1/D2验收。

## 以下为原B基线离线历史记录

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
