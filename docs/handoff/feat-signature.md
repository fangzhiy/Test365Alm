# E 模拟签署交接

## 来源与边界

- 当前负责人：E worker `orca_session_id:64d7fba2-b363-485e-a8b7-7a542f3c4781`，dispatch `ctx_459c810d228c`。
- 起点 `58efd4788596a06a3205603edabadda04ffa58dc`，分支 `feat-signature`，远端 `https://github.com/fangzhiy/Test365Alm.git`；已验收 C/D 已集成。
- 迁移现有25个 V1..V25；E预留V26/失败探针V27。两自动 dirty `.claude/settings.json`、`.trail/hook.cjs` 保留不入提交。
- 仅离线实施；Docker/产品服务/真实浏览器尚未授权窗口，均 NOT_RUN。不 push/PR/merge dev。

## 已完成与下一步

- 契约 `contracts/signatures.md`：三固定签署目标、同会话 OIDC prompt=login/max_age=0 再认证、模拟提供方、不可变快照/摘要、原命令幂等与历史锁定。
- 纯规则3项已 RED（缺少实现导致编译失败）→GREEN；Java21本地全套98单元零F/E/S。日志在忽略的 `local-evidence/signature/rules-{red,green}.log`。使用现有实际终态 FINISHED，不发明 COMPLETED。
- 已读取 OIDC Core 官方规范及 Spring OAuth2 Login Advanced 文档，复用授权码/PKCE/state/nonce，不使用密码grant。
- 下一步：独立提交后提供方/同主体再认证，再按目标逐个服务/DB/API/UI，最后设施闭集及总管审查。
- 不勾选 E1/E2。原 202IT/66focused 是基线事实，本轮真实验证 NOT_RUN。
- 提供方切片：`SignatureProvider` 接口及 `SimulatedSignatureProvider`，证据明确模拟、绑定服务端材料；测试先缺类RED。
- 现有 `DefectService.read` 按 rowVersion 取对应修订，V18闭合外键/guard禁止 CLOSED 更新；因此签署只校验共享强版本，以独立签署唯一键和事务/目标锁排斥并发，不增加业务版本。完成attempt同样保持不变。
- 再认证会话切片：`SignatureLoginFlow` 仅服务器会话产生证明，稳定绑定不随Spring会话ID轮换丢失；现有OIDC解析器按pending添加prompt/login+max_age0，成功handler重查本地主体/项目writer，失败或换主体清除会话。3项流程单元先RED→GREEN，签署专项7项全过；完整真实OIDC仍NOT_RUN。
- V26/服务/API切片：三目标权威JSONB快照/摘要、不可变挑战和签署、目标唯一约束/事务锁/主体成员行锁、范围RLS、审计/Outbox/幂等deferred commit guard；会话证明只在服务端，不持久化token。102单元已全过，10项SignatureDatabaseIT及非空V25升级新增并编译，均尚未实际运行。
- 真实OIDC测试切片：沿用同CookieManager/真实过滤器链，本地IdP只新增可选签名auth_time，旧场景不增加claim；3个HTTP方法覆盖同主体、CSRF/版本、未认证挑战、换主体/缺少或旧auth_time/错nonce、跨会话/撤权/退出。已编译，实际HTTP NOT_RUN。
- UI切片：三个业务入口/历史用例、同源真实IdP弹窗回调、显式确认；前端只传challengeId，不收密码或token。API/面板先缺模块RED→GREEN；初次GREEN存在不完整测试夹具导致1未捕获错误，已补全真实签署夹具，后续零未捕获错误。初次build发现历史attempt版本可空，未伪造版本；只有真实正数版本才能显示运行签署。
- 最新前端实际366项/36文件全过，23新增聚焦项全过，lint/build退出0（保留原有8条lint warnings，没有新增抑制）。14项父组件回归证明same/other、原生Enter、刷新、迟到目录回调不能丢原挑战意图；真实项目/身份/权限/session teardown abort并忽略迟到响应。真实浏览器仍NOT_RUN。

## Offline regression extension (2026-11-11)

- Added two distinct-challenge and content-edit/sign serialization database methods; strengthened existing rollback case to inject both audit and Outbox failures and existing runtime test to attempt VIEWER inserts and updates.
- Java 21 `mvn -B -ntp test`: 102 tests, zero failures/errors/skips (`local-evidence/signature/concurrency-unit.log`); new IT compiled only, PostgreSQL execution remains NOT_RUN pending coordinator resource window.
- Prepared real IdP popup assertions for all three meanings; wrong-password and other-subject negatives are UI source only until the real owned browser run.

### Browser source checkpoint

- Extended the accepted execution and deviation UI flows to reauthenticate and sign their exact terminal snapshots without removing their original assertions; added a fifth exact UI flow for wrong password, same-subject success, signed history/new unsigned revision, viewer read, and other-subject rejection.
- Registered D/E callbacks pass 28 offline ownership cases; lint exits 0 with eight existing warnings and TypeScript/Vite build passes. No browser or product service was started; real UI remains NOT_RUN.

### Self-review corrections before real execution

- A new historical-revision navigation test failed twice with the actual enabled button, then passed after the existing scope lock also guards historical selection (16 parent signature cases now pass). The first selector-only failure and corrected business RED are both retained.
- Corrected the new nonempty upgrade fixture to finish with the real saveStep response's incremented attempt version; no business service or old fixture was changed, and the database regression remains NOT_RUN.

## Fixed offline verification checkpoint

- Accepted source remains `58efd4788596a06a3205603edabadda04ffa58dc`; V1..V25 are byte-identical. Only V26 is new; formal migration failure tool now requires successful V26 then its independent V27 probe.
- `tools/linux-test/combined-methods.json` freezes **102 unit / 218 integration** identities; `linux_test_inside.py` freezes **82 focused = original 66 + E16**. Old 95/202/66 sorted method identity hashes are independently asserted, and all current Java test identities match the fixed inventory. No old methods were removed.
- Added signature report gate (12 database + one nonempty V25 upgrade + three real OIDC methods), retained all existing CI gates and six jobs, and added the E UI identity to full browser report requirements. Local owned runner requires exactly five original-four-plus-E flows, single worker, zero retries/skips; callback admission has 28 offline cases. Current full browser discovery is 16 flows across 7 files (verified by `playwright test --list` after the folder fix); full-suite/A03 acceptance remains coordinator-arranged.
- Actual offline commands: Java21 `mvn -B -ntp test` **102/0F/0E/0S** (`final-unit.log`); frontend `npm run test:run` **382/36 files**, no failures (`review-web.log`); `npm run lint` exit0 with eight existing warnings and `npm run build` pass (`final-lint.log`, `final-build.log`); Python discover **209 passed** (`python-all-green.log`). Package, budget and p0 validators pass as planning/tool checks only.
- Preserved Python RED (`python-all.log`, five obsolete count/probe fixture expectations), repaired all named inventory expectations and V27 fake process marker without reducing a gate, then all 209 pass. Original UI RED/selector RED and historical lock RED/GREEN logs remain under ignored `local-evidence/signature/`.
- Real Linux clean/EICAR then unit/focused/full PostgreSQL/OIDC/upgrade, real dual-SCRAM provisioning, and five-flow IdP UI: **NOT_RUN**, pending coordinator's serialized owned resource window. No Docker, products or browser services were started by this worker, and no runtime resources need cleanup at this checkpoint.
- Planned commands after authorization: `python tools/linux_test_runner.py --source <committed-checkpoint>`; only after success, `python tools/local_browser_runner.py --source <same-checkpoint> --with-assets` (the existing runner takes a committed archive and allocates fresh guarded ports). Retain exact source/run identity and cleanup evidence; do not use daily env or reserved services.
- E1/E2 remain unchecked. Real external signature vendor and compliance certification remain out of scope. The two automatic dirty files remain preserved and unstaged.

## Coordinator-requested execution directory callback repair

- Source: `5ab01b9a621fe83ae53dbe944023015bd14a0cf1`. Coordinator found that a folder placement started before signing could call the old `onMoved`, reset the parent selection/lock, and thereby discard the unresolved signature intent before `loadSets` checked its guard. Captured pre-signature callbacks reproduce the same failure in the actual execution parent and actual SignaturePanel.
- Added 44 actual-parent cases: eight execution challenge/sign × in-flight/unknown × move/select callback cases, four deviation late-create callback cases, and 32 project/identity/permission/reset teardown cases across both parents and both stages/outcomes. Assertions retain selected evidence, prevent refresh/navigation, and compare original body/ETag/key after retry. The deviation parent has no directory reset entry; late creation only changes list items and does not replace the selected detail, so no defect production code was changed.
- RED: `execution-folder-lock-red-corrected.log` has eight execution failures and 36 passes before repair. The first RED additionally caught four test assertions that incorrectly expected an input value in textContent; that log is also preserved. Minimal repair routes directory move and selection through `refreshFolder`, checking the existing synchronous signature scope lock before folder changes or `reset`; genuine scope effects and access-denial invalidation remain unconditional.
- GREEN: `execution-folder-lock-green-synchronized.log` passes all 44. The first post-fix run passed 43 but exposed one component-test click before its reauthentication button became enabled; the helper now waits for enabled state and verifies the real popup-opening call before posting the callback. No retry or timeout increase was added. An initial TypeScript build identified the new Run fixture's missing required rowVersion; it now supplies the canonical run version while signing still asserts exact attempt version7. Both failures remain recorded.
- Full frontend `folder-fix-web-all.log`: 426 tests /37 files passed; lint exit0 retains eight existing warnings; TypeScript/Vite `folder-fix-build-corrected.log` passes. Playwright `folder-fix-playwright-discovery.log` is discovery only: **16 tests /7 files**, not executed. A final full frontend run after the fixture correction is recorded separately.
- Backend102/218/82 inventory, SQL, OIDC/API rules, business assertions, browser selectors/retry/skip and all facilities are unchanged. Backend was not rerun for this frontend-only repair. No Docker, product services or real browser launched, and E1/E2 remain NOT_ACCEPTED pending coordinator review and serialized resource authorization.
- Final current-source frontend confirmation after the Run fixture correction: `folder-fix-final-web.log` **426 passed /37 files**, zero failures. Prior unit102/Python209 evidence still belongs to the unchanged backend/facilities checkpoint; this repair does not claim fresh backend or real-resource execution.
