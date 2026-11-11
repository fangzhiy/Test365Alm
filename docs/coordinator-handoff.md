# 总管交接与当前工作台

更新：2026-11-11。本文长期保留，不属于功能合并后删除的 `docs/handoff/` 文件。

## 接手阅读入口

- [2026-11-11交接快照](handover/2026-11-11-status.md)：main/dev发布基线、A–E验收范围、工作区和未完成事项。
- [暂停后的恢复指南](handover/resume-guide.md)：先核对再恢复、现有Orca任务、F→G顺序和资源/迁移/发布约束。
- [验收证据索引](handover/evidence-index.md)：各阶段总管结论和worker原文、E本机证据路径、历史失败与不可恢复的诊断缺口。

GitHub已同步到 `cc548678cd41ef0e53ae4fe144f1031b3317f4ac` 后追加本交接文档；该值是交接前基线，最新HEAD以Git为准。main是默认分支，dev为集成分支。F/G仍暂停，本轮只整理文档。

## 当前收尾与暂停状态（2026-11-11，优先于所有旧授权）

- 用户先明确“所有暂停”，后授权“收尾，先提交github”并要求推送主分支。本次只完成已验收 A–E 的归档、dev 本地集成与 GitHub 主分支 `main`（设为默认），并同步远端 `dev` 推送；F报告/G A03继续暂停，未经用户明确恢复不派工或启动验证资源。分支约定：`main` 保存已验收成果并作为默认分支，`dev` 做集成，`feat/*` 做功能开发；旧 `chore/r01-status-001` 保留。
- GitHub已完成首次同步：main/dev均为 `41becc532886155cf96f1147dd90e6ea52ad068f`，API及远端HEAD均确认默认分支main；旧默认分支仍保留于 `0c10c38835f112fa41281e824a320f4184cbe08d`。主工作区dev干净，分别配置main/dev跟踪origin同名分支。后续本收尾记录提交同步到两分支，不改变实际被测业务树；CI触发配置覆盖所有分支，新远端CI结果不冒称已通过。
- E1/E2已由总管在 `msg_088209b2a237` 验收，最终worker交接 `6c7b117070b18130c8cbfa78b5e3cfb1bbdb1c73` 相对实际UI源 `3af82c48b97eb4eb01d1b61ea6e9db68fce2f082` 仅交接文档变化。原文逐字归档 `docs/reviews/signature-worker-handoff.md`，正式结论 `docs/reviews/signature-coordinator-review.md`，清单E1/E2已勾选。
- E实际后端源 `c549b25dd962554607ca10dc869326371c86b8c1` / run `8eef9cc7836a4a6698d84fae4587db2f`：scan102/1→unit102→focused102/82→full102/218全逐名零F/E/S；后续后端/设施树未变而获准沿用，不冒称后续SHA重跑。实际UI源3af82c4 / run `r03-local-78aa3de77a98483392c8f61b6a21043c`：完整五身份全部passed，1worker/0retry/0skip，真实S3/ClamAV和正式双SCRAM均通过。
- 总管已独立核验后端62完整容器ID、浏览器4完整容器/1完整网络/3卷及run/project标签空，3秘密和37个PID+Created均消失，owned Java/Node0；Engine一致，保护47容器/22网络/45卷不变。证据 `integrate-audit/local-evidence/e-final-coordinator-cleanup.json`，worker最终索引 `feat-signature/local-evidence/signature/e3af82-final-window-review.json` 的14份哈希已复核匹配。
- 正式迁移实际26份（新增V26），V1..V25 blob未变；V27独立故障探针、16条UI全集、A03及F报告均未在E窗口运行。外部签名厂商/合规认证/部署不在本次验收。所有旧失败及诊断局限保留。
- E暂停通知已由 `ctx_4892bb53646b` / task `task_117b21ef9b8e` 确认完成，无存活owned资源。功能worktree两份原自动配置修改保留，不入提交。主仓库固定dev；外层需求文档用户修改不混入。

## 先前执行状态（历史记录，受上方暂停与收尾范围限制）

- 用户已明确“执行全部”，继续本地实现/测试/审查/集成：审计、业务字段、执行网格、复核、模拟签署与锁定、报告、A03十四用例闭环。Run `run_ac1961a61108`，细则 `docs/oq-delivery-plan.md`，清单 `tasks/oq-delivery-todo.md`。不推送、不部署或接生产，不自行确认业务报告格式认可。
- 目录树已在 `dev@da47293`。审计总管组合后端 `34c19f6` 完整85单元/167集成真实S3/ClamAV/EICAR全绿，cleanup true；真实浏览器源 `4a13589` 的用例/执行/缺陷三条Keycloak流程全部passed，单worker、0retry/skip，exit0、cleanup/processes_stopped均true。总管已独立核对脱敏JSON、实际源码相同性及审查结论，批准本地dev集成。
- 审计交接原文归档到 `docs/reviews/audit-history-worker-handoff.md`，正式验收在 `docs/reviews/audit-history-coordinator-review.md`。旧Windows Defender导致的失败保留历史事实，未更改安全软件或降低真实扫描断言。
- B业务字段worker `ctx_75ff43d25b84`、C执行网格/运行器worker `ctx_625abb4f3c84` 及D复核worker `ctx_9ed975ccb1ec` 已完成各自验收。正式迁移现含B V23、C V24、D V25，共25个实际文件，旧V1—V24不改。D线故意失败探针V26未执行；E正式迁移预留V26，探针顺延V27，实施时按实际文件逐名核对。
- D复核依赖B，E模拟签署/锁定依赖C/D，F报告依赖E，G最终A03验收依赖全部合流。D已集成本地 `dev@58efd4788596a06a3205603edabadda04ffa58dc`，主工作区干净，运行树与a27d306一致；总管归档文档增量 `trail check --base a27d306` 检查6文件无冲突、8项非阻塞词语提示，理由已复核。E任务 `task_c0b740c62973` 已从该确切基线启动：worktree `feat-signature`，dispatch `ctx_459c810d228c`，worker `structworker_76018a86-ea7d-4b91-871c-cedd45909f85`；续派消息 `msg_31d4f8b828a7` 规定先离线契约/小切片/逐名门禁，正式V26/探针V27，真实资源待源审查和串行窗口，不把代码检查点当验收。后续纯文档提交不改变E正式依赖源。
- 审计、B及C本轮测试资源已精确清理；后续重型Docker验证仍严格串行。W1演示与其他项目资源继续保持，不复用未知资源、日常.env或5173/8080。
- B业务字段本轮B1/B2已完成总管验收：后端源da9906c的89单元/175完整集成、浏览器源b5cc1b2的三条真实流程全部通过，报告/运行源码一致性及精确清理均已独立核对；最终worker交接b2d9ec2归档为 `docs/reviews/business-fields-worker-handoff.md`，正式决定见 `docs/reviews/business-fields-coordinator-review.md`。批准本地dev集成；独立故障探针和完整14条浏览器全集仍NOT_RUN。
- C已完成总管独立验收：后端源96def34/run d3142b41的scan93/1→unit93→focused93/51→full93/187全零F/E/S，逐名库存匹配；浏览器源8f48057/run7dfd6f17的三条真实Keycloak流程全部passed，1worker/0retry/0skip，real-owned S3/ClamAV及正式双SCRAM provision通过。总管同Engine复核后端53容器、浏览器4容器/1网络/3卷及标签均无残留，秘密删除、同root Java/Node0。最终交接ba65160仅文档变化，原文归档 `docs/reviews/execution-grid-worker-handoff.md`，正式验收 `docs/reviews/execution-grid-coordinator-review.md`；C1/C2批准本地集成，旧失败及cleanup=false记录保留。
- C已实际集成本地 `dev@1b5ee2353f4b36ababb835dce2ababd1b98c0b9c`。D在该基线完成组合后已获总管D1/D2最终验收：后端6fa15ce/run5222fc82的scan95/1→unit95→focused95/66→full95/202全部逐名零F/E/S，浏览器afa0f4a/run77c0d60c显式with-assets四条真实Keycloak UI全passed、单worker零retry/skip，真实扫描与正式双SCRAM通过。总管自行Docker核验57后端完整容器ID、浏览器4完整容器/1完整网络/3卷及标签均空，三秘密文件删除、同root Java/Node0。最终交接a27d306仅文档变化，原文归档 `docs/reviews/test-case-review-worker-handoff.md`，正式验收 `docs/reviews/test-case-review-coordinator-review.md`；旧3P1skip及原网络12位限制保留。批准本地dev集成，正式V25/旧V1..V24不改；V26独立故障探针/15UI全集仍NOT_RUN。E模拟签署与锁定从本地已集成D开始，正式V26/探针V27，先离线实现与审查，资源继续严格串行，不提前启动。

### B/C 真实验证前检查点（2026-11-11）

- 以下为B/C过程检查点；当时前置验收基线为 `8bd7408e3c7dc1f65ed19721d40c98136b0fa5b7`，包含审计后续设施补丁 `bdb9991` 和复核说明。最新B验收及C/D调度见上文；以下数字只属于各自分支，不能作为组合通过结果。
- B 固定 `52e77354205a08050cc64d02b62da6fe2cafe3f3` 执行真实验证：scan 89单元/1真实扫描、unit 89均通过；focused 8项为7通过/1error，全量175被门禁阻止，未启动。run `8d25f317e41b4df08e440eca8e6cb715`，总管已独立读取scan/unit/focused/run脱敏JSON，核对退出0/0/1、失败方法及 `cleanup=true`，完整集成报告不存在。worker定位为夹具撤权后正向重放仍指定同一已撤权复核人；B仅修正正向测试人员，保留全部失效拒绝及重放重新授权断言，提交后重新执行全部阶段，保留旧失败证据。
- B仍持有重型Docker独占时段；C只离线推进，不启动Docker或服务。总管已答复，无待用户批准事项。B固定设施顺序为 scan(89/1) → unit(89) → focused(89/8) → 唯一full(89/175)，任一非零、缺名、重复、失败、错误或跳过均停止。
- B已提交修正 `7433ef2c4beaa33d4746c0b2df70c472be9b1dd2`：正向重放指定有效 `f.admin`，撤权发起人 `f.member` 后先断言admin仍在候选中，再断言发起人重放被拒绝，分别覆盖两种撤权边界。总管已核对该diff。
- 7433ef2真实run `c50d90143f4f46b39f8e68ba4f1817e7`：scan89/1、unit89、focused89/8全过，full89/175为173通过/2failure/0error/0skip，`cleanup=true`。总管已独立核对四阶段及run脱敏JSON。失败仅 `AssetMigrationUpgradeIT.v19NonEmptyDatabaseUpgradesToV20WithoutLosingExistingBusinessHistory`、`ReleaseMigrationUpgradeIT.nonemptyV18RequirementsTestsExecutionDefectsAndTraceSurviveV19AndRemainUsable`。worker报告原目标/latest旧列快照均过，旧key冻结DTO相等断言失败；已单元RED定位Jackson3将旧JSON null读为NullNode。修复只在四类响应DTO构造时规范化为Java null，保留非空/空对象响应、输入校验及原DB相等断言；独立提交和新确切SHA完整验证尚待回报。
- B已保存响应null修复/经验 `e08140f`、数字节点规范化 `7749951`、历史复核人姓名展示 `da9906c6967edd53bfde5808eb405a47b7106571`；当前固定da9906c执行既定全部阶段，尚无该SHA完整结果。总管已读修复和回归：新快照按实际JSON往返消除LongNode/IntNode表示差异，保留JSON值、不回写历史，并增加真实DB同修订no-op断言；四类响应覆盖null/空/非空往返，输入显式null仍拒绝。姓名通过授权目录解析并标注“当前成员姓名”，不伪造历史冻结姓名；父审计刷新逻辑未改。worker报告Java单元89、前端262/lint/build通过。B继续独占时段，全部后端通过/清理后可直接进行同业务源码的三条真实浏览器验收，无需再请求既定范围许可。
- da9906c后端已完成：run `16077b4faf6c450287f50b31103f07d2` 的scan89/1、unit89、focused89/8、完整89/175全部零F/E/S、exit0、cleanup=true；总管已独立核对报告及逐方法唯一身份。三条同SHA真实Keycloak浏览器正在运行，B继续独占，最终验收/合并待浏览器与精确清理。复核证据汇总见 `docs/reviews/business-fields-coordinator-review.md`。
- da9906c首轮浏览器run `bb35d43bc533419a9fc4ee1f9c07db1e` 已结束：缺陷和执行流程passed，用例创建/编辑/历史/viewer流程failed，exit1；单worker、0retry/0skip、cleanup/processes_stopped=true，总管已独立核对run/results。B继续定位，C保持等待，未批准合并。只改测试选择器且运行源码一致时不要求无关重跑后端；改业务源码则按实际影响重新验证，保留全部旧失败事实。
- B历史内容夹具 `197ac06114e0648b25301b698e855ea6c233f9d4` 冻结于 `61711294ff10e16b8df29418badaa8b455bc80ea`。总管逐行对比四个实现，SQL/行为差异仅为批准的测试类名、组件移除、父构造及兼容DTO复用；升级测试保留旧target非空快照/幂等断言，latest后改用正式服务。该静态核查不替代真实升级通过。
- C最新检查点 `70d7cfae502bc83bc90e430edf5ff8b3b6c09ef0`，包含历史执行/资产兼容 `f9df894b68cc24c95d1393e58baf1bb1a3d08c0f`。worker报告89单元、263前端及29项相关Python离线检查通过；179集成库存，DB/OIDC/浏览器均NOT_RUN。历史内容服务由B负责，执行/资产兼容由C负责；两边独立分支修改共享升级测试，总管合流保留两边，不整文件选一方。
- 总管已答复C先待B验收后合流B+C，再跑确切组合，避免先做C单线完整179。固定C专项43项：ManualExecutionDatabaseIT31、ManualExecutionMigrationUpgradeIT4、AssetDatabaseIT步骤证据3、真实OIDC手工执行1、Asset/Defect/Release三个升级各1、AssetProvisioningUpgradeIT的upgradedDatabaseUsesProvisionedRestrictedScannerLogin1；加B专项8，组合专项暂定51。93单元/187完整集成仅预估，合流必须逐名复核。批准复用B设施55490a9并独立提交固定selector/计数适配，暂不启动资源。
- C组合设施检查点 `b708b7f55a91305adb654f01c81eb4c94a943386` 已固定51个专项方法身份及扫描预检确切方法，拒绝缺失/替换/重复与F/E/S；完整集成必须包含这51项。总unit/full暂留85/167使未合流版本失败关闭，不冒称组合库存；合流后另提实际计数供审。worker报告相关离线共46项通过。C按明确安排结束当前轮等待B，不是任务完成；Dispatch仍开，收到B最终验收SHA/清理后以send续派，不因停止通知擅自重试或启动资源。
- 70d7cfa新增显式 `--with-assets` 真实固定digest S3/ClamAV、归属/daemon/端口守卫、正式独立scanner LOGIN及SCRAM核验。总管已静态审查并独立运行7项新增Python设施回归通过；真实执行仍NOT_RUN，窗口尚未放行。assets disabled不能验收C步骤附件。B通过后仍需真实姓名选择、字段保存重载与历史流程；B验收合入后才启动D并指定确切基线和迁移号。
- 复核资格跨模块决定：新指定复核人必须是有效project writer（现有admin/member）；viewer可读目录，不能被指定为可批准人。D动作时重新核验，不从租户角色或历史指定推断当前批准权限。

## 当前负责人和交接状态

- 用户最新原话：“现在你是总管，你们两个总管交接好文档”。当前外层 Wetest365ALM 的 Codex 接任总管，负责方向、拆任务、答疑、验收及最终合并决定。
- 当前总管邮箱：`orca_session_id:codex_05223c99_c316_4acc_bfd6_b2a2fcca04ff`。
- 原 Claude 总管邮箱：`orca_session_id:claude_94da3475_ec0c_41a0_9aec_0af745ae8239`。它转为交接方，不再派新任务或操作 dev；以后恢复额度也不自动恢复总管身份。角色再次变更以用户新指令为准。
- 旧会话中“Claude 始终是总管、不得交给 Codex”的偏好已被本次指令取代；功能内同 worktree 接力、主文件夹只做集成的规则继续有效。
- 已向原总管发送交接请求，回执 `msg_c99c7dcf243b`。截至本文初稿，未收到原总管的新确认；旧会话最后可见结果为服务 503。发送成功不等于对方已完成核对。
- 接任已生效；交接事实依据本地会话、Git、功能交接及 worker 报告。原总管待补充的事项见末节。

## 目标和边界

- 沿用现有 Test365Alm，自用，替换内部 OpenText ALM 24.1；先演示核心闭环，以 A03 的 14 个手工用例为验收锚点。
- 先做目录、执行、业务字段、复核、模拟签署、审计、报告；历史数据迁移暂缓，不为商业化或全量 ALM 菜单扩范围。
- 电子签名先模拟并保留替换接口；真实厂商接口、部署、GMP 验证及迁移策略后续确认。
- 保留自动化测试和安全数据约束；无需每轮成套证据文档，不把规划、测试总数或历史 CI 当本轮业务验收。
- 接任初稿只改文档；用户后续授权继续并“执行全部”，现按上述最新状态推进本地业务集成。不推送、不开PR、不停演示、不删除仍被使用的worktree。

## 仓库和入口

| 用途 | 路径或入口 |
|---|---|
| 外层总管工作区、需求文档 | `D:/code/ProOrca/Wetest365ALM`，需求在 `zl/` |
| 代码主文件夹 | `D:/code/ProOrca/Wetest365ALM/Test365Alm`，固定 `dev`，只集成 |
| 代码远端 | `https://github.com/fangzhiy/Test365Alm.git` |
| Orca 代码 repo id | `7d777de0-65fb-4c9d-bb61-96d9ab74c24f` |
| 总纲 | `docs/alm-replacement-roadmap.md` |
| 当前总管状态 | 本文件；优先于旧会话及私有记忆中的角色说明 |
| 共享经验 | `.trail/learnings/`；先读相关 trail 结果 |

主工作区中的本文件是稳定入口。工作区、分支或会话有变化时先核对实际状态；Orca 运行时重启后重新发现地址，不盲目重用旧身份。

## 接任时的分支快照

以下为接任前读到的事实，不代表后续分支永远停在该提交。本文进入 dev 的文档提交不计作业务进展。

接任文档已通过 `a14d7d4` 进入本地 dev。该提交使 dev 与 `r2-merge-w2@60e1989` 分叉，R2 旧报告中的“dev 可直接快进”已经过时；后续先在独立集成 worktree 合并双方，再由总管审查，不重置 dev 或丢掉交接文档。

| 工作线 | 分支／提交 | 状态和下一步 |
|---|---|---|
| 集成 | `dev@a667343` | W1 已合入；W2、审计未合入 |
| W1 演示 | `w1-demo-env@d6e8bfa` | 通过 R1 集成进 dev；资源原属 W1，不删工作区 |
| W2 目录树 | `w2-folder-tree@b4e602d` | 已实现，R2 审查后形成下一行提交 |
| R2 目录集成 | `r2-merge-w2@60e1989` | 包含合并 `b7f4141` 和演示目录播种；未进 dev |
| 审计历史 | `feat/audit-history@a797f3f` | 三入口已接通，收尾审查／集成／真实浏览器验收中 |
| 总管文档 | `docs/coordinator-handoff` | 从接任时 dev 建立，仅交接文件和规则入口 |

外层用户正在修改 `zl/Orca+Trail-Code安装配置指南.md`，不混入交接提交。多个工作区的 `.claude/settings.json`、`.trail/hook.cjs` 存在自动刷新改动，不混入功能或交接提交，不擅自清理。

## 各线证据和限制

### W1：演示已集成

- `python tools/demo_up.py`；默认前端 `http://127.0.0.1:15173`、后端 `18083`。5173、8080 属于用户其他项目，不能动。
- 报告已验证空演示卷启动、重复启动、真实 Keycloak 登录、14 用例各 3 步、1 测试集 14 实例、11 PASS／2 FAIL／1 RUNNING、1 偏差。
- R1 报告 Python 142、Java 单元 66、前端 213、lint/build 通过；未在 R1 重跑后端完整集成，因为后端／迁移未改。
- W1 报告当时演示保持运行；交接本轮未重做存活检查，不能仅凭旧报告声称现在仍健康。密码只在忽略的 `.env.demo`，不复制入文档或消息。
- 资源清单绑定 `C:/Users/22538/orca/workspaces/Test365Alm/w1-demo-env`。迁移演示需在原位置按归属保护正常停止，再迁移／重建；不直接删卷或目录。

### W2／R2：代码已审查，最终集成未执行

- 共用目录树、归属移动、分页前目录过滤、RLS／CSRF／版本条件／幂等／审计 Outbox；正式迁移 V22、故意失败探针 V23；旧 V1—V21 不改。
- R2 报告：69 后端单元、224 前端、145 Python、lint/build、trail check 通过。完整集成 162 项，2 failures／21 errors／0 skipped，与 W2 的 162 个方法状态逐项一致；不能写全绿。
- R2 修正 README 探针说明，并为新建／中断续建演示增加两层目录归档。已完成演示检查点保持只读，不能删检查点强制重播。
- 交接报告：`r2-merge-w2:docs/handoff/r2-merge-w2.md`；脱敏证据在该工作区 `local-evidence/r2/`。
- 未做真实浏览器目录 E2E；目录 DB 精确名称门禁、目录浏览器回归为后续建议。
- 原总管最后停在检查交接文件清理策略，随后服务 503；尚未快进 dev。保留报告的验收事实后，按规则删除功能交接、更新路线图，再决定集成，不能把本轮文档合入误写成 W2 已合入。

### audit-history：实现完成，验收待完成

- 工作区：`C:/Users/22538/orca/workspaces/Test365Alm/audit-history`。
- Codex 邮箱：`orca_session_id:codex_647f15f5_b420_4207_8e80_922dd07246c9`；聊天“继续审计历史前端面板”。
- 后端接口 `GET /api/v1/projects/{projectId}/history/{objectType}/{objectId}`；用例／缺陷对比不可变修订，运行读取 `execution_event.payload`，不能从只有哈希的 audit_event 编造旧值／新值。
- `historyApi`、分页 HistoryPanel、缺陷／用例／运行入口已完成；记录变化自动刷新。历史 403／404 局部处理，不能关掉父级详情。
- 交接报告：该工作区 `docs/handoff/feat-audit-history.md`，优先读顶部 Codex 接力更新，下面旧“下一步”已部分完成。
- 报告：234 前端、lint/build 通过；72 后端单元通过，HistoryDatabaseIT 5 项通过；完整集成 157 项仍 2 failures／21 errors／0 skipped。
- 三条真实 Keycloak 流程已补历史断言，尚未实际运行。合成夹具浏览器检查通过，只证明组件行为，不等于真实业务 E2E。
- 原转达 `msg_00dd0c6f8165` 已送达，Codex 正在独立推进审查与排错；应再告知总管已改为本 Codex，后续汇报发当前总管。

#### 后续进度：收到了审计 Codex 的排错报告

- 来源 `msg_e5d888f86885`：已读总管文档，保留全绿门槛，不移动 dev。发现缺陷复测／同版本手动刷新不重载历史，先红后绿修复后前端 235 项通过。
- worker 在新建且归属核验的容器复现：Docker Desktop 暂停时 `NetworkSettings.Ports` 为空；旧 `pauseScanner` 在调用方 try/finally 之前抛错，导致未恢复。修复保留标签、容器 ID、启动时间、配置绑定检查，恢复后严格核验原发布端口。
- worker 报告原有 `scannerUnavailableAndTimeoutRemainRetryableWithoutPublication` 先失败后通过；完整后端集成仍在运行，尚未证明原 2 failures／21 errors 全部消除。总管尚未审查最终 diff 和原始脱敏证据，不能写成整组故障已解决。
- 总管回复 `msg_076575e72744`：继续完成当前测试；审计刷新修复与附件测试设施修复分开提交，补异常路径恢复及归属拒绝证据，报告完整结果和真实 Keycloak 浏览器验收状态；不移动 dev、不推送。

#### 后续进度：完整集成已结束，剩余 EICAR 环境干扰

- 来源 `msg_be485458b72d`：完整集成 157 项，1 failure／5 errors／0 skipped（先前 2 failures／21 errors）；worker 报告扫描器连锁故障消除，但整套仍失败。
- 六项剩余失败均发生在 EICAR 上传：`AssetDatabaseIT.realClamdCleanAndEicarProduceSafeTerminalStates`、AssetTerminalOutcomeIT 四个 infected／terminalReplay 方法、OIDC terminalComplete 方法（PUT 预期 200，实际 503）；完整准确方法名待最终交接补齐。
- worker 报告本轮时间窗 Defender Operational 1116 恰有六条 `Virus:DOS/EICAR_Test_File` 检测，对应 Java `test365alm-upload` 临时文件，另有 1117 隔离事件；脱敏证据在其工作区 `local-evidence/audit-history/defender-events.json`。未修改安全软件设置。总管尚未独立核查证据，不能将相关性直接写成六项均已验证修复。
- 七项隔离守卫单元回归通过：暂停后检查异常仍恢复原容器；归属／替换／启动时间／配置不符拒绝；恢复端口不符仍失败。抽取共用守卫后正在跑全单元及两个真实暂停恢复方法，不并行重跑完整集成；两类修复待分提交。
- 总管回复 `msg_306ad29ea17b`：先完成当前回归、分提交、保存交接；评估在本机 Linux 容器内运行 Maven 并保留 EICAR 临时文件于容器文件系统的最小方案，保持真实 ClamAV／原断言及资源守卫。不能安全执行时记 BLOCKED；不改 Defender 设置、不推送触发 CI、不伪造 CI-owned 身份，不改生产上传语义。
- 三条真实浏览器流程仍 NOT_RUN。完整集成全绿及真实验收门槛不变，未批准业务合并。

#### 后续检查点与 Linux 测试设施批准

- 来源 `msg_40f40e14c655`：业务刷新修复 `814d5db`、附件测试设施修复 `4764d63`、交接 `a45bfcd` 已分别保存；起点 `a797f3f`。最终 79 单元（含七项守卫）及两个真实暂停恢复方法通过，均 0 failure/error/skipped；前端 235 项、lint/build 通过。完整集成仍为先前 157 项 1 failure／5 errors／0 skipped；三条浏览器仍 NOT_RUN。
- 总管已读取该检查点交接并核对本机 Engine 为 Linux Docker Desktop 29.7.2；批准在本机制作固定 digest 的受控测试镜像（Java21/Maven/Python3/Docker CLI），仅复制 Git 受控提交快照进入容器可写层，tmp/target/cache 不绑定 Windows。现有 Compose manifest 不能冒充覆盖裸 runner，需专门的归属守卫及离线回归。
- 批准本轮测试 runner 挂本机 Docker socket，以使用兄弟 Testcontainers；socket 具有 daemon 完整权限，标签守卫只是操作限制，不是权限隔离。仅受控测试和工具使用，禁止 privileged、DinD、host PID、宿主根挂载、全局配置变更及操作未知资源。
- 操作／清理核对 daemon/context、本轮随机标签及精确资源身份，保留 Ryuk；归属或 daemon 改变拒绝，不批量按前缀删除。先验证守卫、连通及真实扫描，再在明确快照运行完整测试；只拷出脱敏报告，不拷出 EICAR 或临时文件。
- 镜像与最小测试工具是本次批准的新测试依赖范围；不升级生产依赖，不改 Defender／生产上传，不复制日常 .env／未提交文件／凭据，不推送或创建远程资源。测试设施独立提交；不授权移动 dev、业务合并或放宽真实浏览器验收。

## 附件失败与合并标准

### 总管继续推进的集成检查点

- 当前总管在 `integration/folders` 独立工作区集成 `r2-merge-w2@60e1989` 与最新 dev，总管文档和目录业务无冲突；不修改目录运行实现。归档 R2 交接到 `docs/reviews/folder-tree-integration.md`，更新路线图第 7 节，不把整个阶段 1 标完成。
- 本次实际通过 Java21 单元 69、前端 224、Python 145、lint/build；目录线沿用原先明确的局部演示集成决定，完整资产失败仍单独记录，不冒充全量绿色。审计的全绿／真实浏览器门槛保持。
- 原 R2 Dispatch 属于旧总管 Run，续派被 Orca 以 consumer_fenced 拒绝；未绕过身份限制或改旧任务结果，改为当前总管自行集成。原工作区／演示资源保留。
- 总管已复核审计还原／权限／分页、面板请求生命周期、缺陷刷新 `814d5db` 及 scanner 修复 `4764d63`；检查点在 `docs/reviews/audit-history-coordinator-review.md`，仍待 Linux 全集成和真实浏览器验收。
- 审计 worker 已独立提交 Linux runner 检查点 `ef27b7a`，含七项 Python 守卫与三项 Java 标签 SPI 回归；正串行执行固定快照的构建、连通、真实 ClamAV/EICAR 与 82 单元／157 集成，结果未结束。已通知无需等待新的既定范围批准。

- 已知非通过集中于 AssetDatabaseIT、AssetMigrationUpgradeIT、AssetProvisioningUpgradeIT、AssetTerminalOutcomeIT 和 OIDC 的三条附件 HTTP 方法。
- 接任时故障现象包括扫描服务不可用、容器端点检查失败、存储不可用／请求超时；“恢复后端口映射失效”是旧假设。上节 worker 新复现的是暂停时 Ports 暂缺引发异常、扫描器未恢复；仍需总管审查和完整测试证明其对整组失败的解释范围。
- 原总管对 W1／W2 曾接受“与既有附件基线逐方法完全一致、无新增失败”作为局部演示集成依据；这不是测试通过，也不是所有后续功能的自动豁免。
- 审计最新交接要求完整集成全绿后才能合并。当前总管保留该门槛；先排错并拿证据，未全绿时审计保持待验收，不自行扩大豁免。
- W2 的历史局部接受与审计的全绿要求分别记录。后续若调整验收标准，写清适用分支、失败方法、理由、风险和决定者，不静默放宽。
- 不删测试、不跳过、不用重试或历史绿色掩盖；测试只针对本轮归属可证的隔离资源。

## 接任时的执行顺序（历史计划，当前依赖见顶部）

1. 完成交接文档、双方通知及共享入口；记录原总管是否回确认。
2. 复核 R2 报告和实时 diff／祖先关系，处理交接归档及路线图状态；再独立决定 W2 集成。本次不提前执行业务合并。
3. 通知审计 Codex 在 W2 集成后同步最新 dev，保留目录树和历史入口；等待前推进独立审查与附件故障取证。
4. 在符合资源归属门禁的隔离环境运行审计的真实登录流程，固定单 worker、零 retry；缺环境明确 NOT_RUN／BLOCKED，不伪造 CI-owned 身份。
5. 满足验收条件后审查、测试、trail check，再合并审计、删除功能交接、更新路线图阶段 2c。三个历史入口存在不等于阶段已验收。
6. 再推进业务字段／手工运行器／执行网格及复核，之后模拟签署／锁定／报告；按最新路线图与演示缺口拆小任务，避免并行改同一面板。

路线图第 7 节仍是早期状态，接任时未准确反映 W1 已集成、W2 待集成、2c 实现待验收；更新必须基于集成事实，不整阶段一律标完成。

## 操作与通信

- Java 21：`D:/code/ProOrca/Wetest365ALM/.tools/jdk-21`；系统默认 Java 17，Maven 前显式切换。Maven 3.9.14；目标 Node 24 LTS，本机已有记录为 Node 26，不能称目标环境已验收。
- 真实测试报告和诊断放忽略的 `local-evidence/`／target；不提交原始 Cookie、token、realm、环境秘密或未脱敏日志。
- 当前 Orca CLI：`C:/Users/22538/AppData/Local/Programs/orca/resources/bin/orca.exe`；先加载版本匹配技能。发消息用已发现的邮箱，发送成功只证明入队。
- 历史 worker 身份：W1 `task_8967cf745fe7 / ctx_611cc592e0a8`；R1 `task_250dc261d586 / ctx_2e0031111d40`；W2 `task_cd894adf86d8 / ctx_b1fbeb152991`；R2 `task_bda755762fce / ctx_947631193d90`。这些是旧总管任务，不能假定已转入新总管的 Run 或自动改结果。
- R2 原 payload outcome 为 failed（集成退出码 1），不得篡改成 succeeded；新总管可以另写有范围的集成决定。
- 继续工作前读 AGENTS.md、本文、当前功能交接、git log/status 和相关 trail 学习；关键决定写共享文件，不只留私有记忆。

## 原总管待核对

- [ ] 确认收到接任通知，停止旧总管调度。
- [ ] 补充未落盘的用户决定、未读问题、仍在运行的任务／资源。
- [ ] 确认 R2 最后检查结果、附件基线接受范围及未记录的风险。
- [ ] 对本文补充遗漏；由当前总管单点编辑，避免同时改文档。

文档入 dev 后已再次通知原 Claude 总管（`msg_7b136488b5ec`）和审计 Codex（`msg_3754b440b57c`）；两条发送均成功，原总管仍待回复。

收到确认或发现新事实后追加日期和结论。服务不可用时保持“未确认”，不代替原总管签字。
