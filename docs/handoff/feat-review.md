# D 用例复核离线检查点

## 最新真实验证与浏览器准入修复（2026-11-11，优先于下文）

D1/D2仍未验收，不可worker_done。总管放行的确切源 `6fa15ce79a91158e5a61dc2258be3366d1998a33` 已完成真实后端，首次四UI验收失败关闭；当前仅修复浏览器准入与归属证据设施，等待总管审查新源后明确放行四UI，不自行启动资源或机械重跑未变后端。

### 已执行事实与清理

- 后端run `5222fc820d024cc49cbc63f285a1ae67`，context `default`，Engine `a6f010ac-61a2-4544-b133-c2b0d92d11d3`：固定scan95/1（真实clean/EICAR）→unit95→focused95/66→唯一full95/202全部exit0、逐名库存匹配、零F/E/S；包括D12数据库、1项非空V24→V25、2项真实OIDC。runner cleanup=true，独立核对57个完整捕获容器ID均不存在，同run的容器/网络/卷标签为空，前后Engine一致。
- 浏览器run `r03-local-1ab1d837c94c459990029a13670a5e2a`，project `test365alm-r03-local-1ab1d837c94c459990029a13670a5e2a`，context `desktop-linux`，同Engine：显式with-assets，真实S3/ClamAV预检及正式scanner两独立SCRAM连接通过；原缺陷/执行/用例三流程passed，D designated-reviewer流程skipped且expected=skipped。Playwright aggregate虽为passed，严格四身份门禁正确exit1，不能验收。
- 浏览器cleanup=true、processes_stopped=true；独立核对四个完整容器ID、三个确切卷名不存在，原始捕获网络12位前缀不存在，同project/run容器/网络/卷标签为空，三个秘密文件已删除，该snapshot所属Java/Node进程为0，Engine一致。**本轮未捕获完整网络ID，12位限制永久保留，不能事后补写成原始完整证据。**
- 原始脱敏证据保留于忽略目录 `local-evidence/linux-tests/5222fc820d024cc49cbc63f285a1ae67/` 和 `local-evidence/local-browser/1ab1d837c94c459990029a13670a5e2a/`，包括run、逐阶段报告、独立cleanup与进程核验；不导出原始Maven日志、EICAR或秘密。

### 本次最小修复与离线结果

- 独立本地提交：`0efbbbf` 为D准入修复及14场景实际回调回归；`434c144` 为业务前完整归属快照、严格结果守卫补充、资产失败夹具适配及新learning。两者均未推送。
- D回调原先误用CI-only `isOwnedCiRun`；仅改为原三流程已用的 `isOwnedRun`，保留随机run/source与local-owned/CI-owned边界及全部业务断言。新 `reviewBrowserOwnership.test.ts` 导入实际spec和归属实现，验证确切D回调注册一次并执行：合法local/CI到达首次page.goto，12类unowned/缺失/错误归属在此前拒绝，且不访问真实Docker。旧实现真实RED为1失败/13通过，修改后14通过。
- 独立设施新增 `ownership_start`：create并验证manifest后、compose start及任何业务前写 `ownership-start.json`，捕获四容器完整64位ID及Created/Image、网络完整64位ID及其实际Created/Name/Driver、三个确切卷名，以及run/source/project/context/Engine；只输出白名单元数据，不输出Env或Options。前后复验Engine与manifest，拒绝错误标签、缺失/替换/不完整ID或重复身份。下一轮必须保留这份原始起点证据。
- Python回归覆盖完整捕获、逐资源逐标签反例、缺失属性、替换ID、写入顺序及秘密不导出；原四身份门禁另加强每条expected-skipped/status-skipped反例，原缺失/替换/重复/非零retry拒绝保持。既有资产失败夹具仅适配新快照边界，保留原清理/脱敏/进程断言。
- 当前完整Vitest **343通过/33文件、0失败/跳过**，完整Python **201通过**；lint exit0（8项既有warning），build exit0，Playwright list仅发现15项/7文件；原M05/M06逐名前端门禁对同343 JSON通过。RED、中间失败与最终日志均保留于 `local-evidence/review/`；最初完整Python出现2个资产mock夹具错误，适配新边界后全绿，不覆盖旧失败。
- `apps/server`、`tools/linux_test_inside.py`、`tools/linux_test_runner.py`、`tools/linux-test/combined-methods.json` 相对6fa15ce逐字不变，固定95/202/66及selector/阶段、旧迁移与业务断言不变。`git diff --check`通过，限定6fa15ce基线的trail check无冲突；新增独立learning `l-4474239b`，旧learning未改。

下一步仅在总管审查新确切源并明确窗口后运行同源显式with-assets四UI，1worker/0retry/0skip，执行前保存完整ownership-start，完成后按捕获身份和run/project标签独立精确清理核验。独立V26故障探针与完整15UI全集仍NOT_RUN，不混入本次四UI验收；没有push/PR/merge dev，两份自动dirty保留不提交。

## 最新父组件复核意图修复（2026-11-11，优先于下文）

总管对 `b8c1bbe` 静态审查及独立42项守卫核对确认旧迁移/夹具与95/202库存保留，但发现父组件导航可卸载ReviewSession并丢失未知结果原意图，暂未放行Docker。本轮按续派仅修复这个前端边界，D1/D2仍待真实验收。

修复只在 `TestCasesPanel.tsx`：同步记录复核锁所属project/完整accessKey/resetSignal，`load`、`selectTest`在同scope锁期间先返回，不能取消原请求或清空selected/detailReady；列表同条/其他条、查询输入和Enter表单、冲突查看最新遵守锁；原刷新/分页/目录禁用保留，迟到目录移动onMoved也经过load守卫。完整accessKey驱动scope读取，覆盖身份变化但权限相同的情形。真实项目/身份/权限/会话切换以及服务端范围失效仍正常清空、abort并隔离旧响应；没有将原命令搬到新持久框架，也不以新key恢复。

可达性核对：刷新用例/Enter查询/分页/目录选择与onMoved汇入load；列表同条/其他条及冲突查看最新汇入selectTest；内容保存后的内部详情刷新原本受contentSaving互斥；历史修订只另开只读article，不替换当前ReviewSession，现有目录/历史刷新和附件权限失效回调保留。App项目/权限props与ReviewPanel确切scope key负责真实teardown。

新增 `TestCasesPanel.reviewIntent.test.tsx` 实际组合父组件、ReviewPanel与FolderTree：9个导航入口×unknown/in-flight，原command/前三scope参数/ETag/key在第二次act保持完全相等且仅两次调用；另含迟到目录移动回调、成功及确定400/403/412/422解锁、401/404范围清空、项目/同权限新身份/viewer/read-loss/reset切换和迟到成功/失败隔离。最初32项为19失败/13通过（同条/其他条、Enter、迟到onMoved与同权限身份切换真实暴露问题，其余失败包含新增控件禁用断言）；修复后原相关66项通过，扩展后41项新回归在全套实际通过。初次TypeScript编译发现新夹具多余字段与TestingLibrary不支持exact参数，已修正，最终build成功。

实际离线：完整Vitest **329通过/32文件**，0失败/跳过，JSON `local-evidence/review/intent-vitest.json`；M05/M06既有名称门禁对同JSON通过。完整Python **198通过**；lint exit0（8项既有warning，原复杂依赖表达式warning消失，无新增），build exit0；失败RED和中间结果在同忽略目录保留。后端与tools/全部4条e2e脚本相对b8c1bbe逐字不变：仍95单元/202full/66focus固定库存及原selector/阶段，故未为纯前端修复重跑后端。

新经验 `l-2c2b1181` 记录父组件卸载为何会丢未知结果、Enter为何绕过disabled按钮，以及scope teardown为何必须继续工作；旧learning未修改。未启动Docker、服务或真实浏览器；未触碰W1、日常.env、Defender、禁止端口；无push/PR/merge dev。下一步总管审查此独立修复SHA并明确既定独占scan95/1→unit95→focused95/66→唯一full95/202，以及同源with-assets四条真实UI窗口；真实验证与精确清理仍NOT_RUN。

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
