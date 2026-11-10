# E 模拟签署交接

## 来源与边界

- 当前负责人：E worker `orca_session_id:64d7fba2-b363-485e-a8b7-7a542f3c4781`，dispatch `ctx_459c810d228c`。
- 起点 `58efd4788596a06a3205603edabadda04ffa58dc`，分支 `feat-signature`，远端 `https://github.com/fangzhiy/Test365Alm.git`；已验收 C/D 已集成。
- 迁移现有25个 V1..V25；E预留V26/失败探针V27。两自动 dirty `.claude/settings.json`、`.trail/hook.cjs` 保留不入提交。
- 当前真实窗口已在首阶段失败后结束，后续离线定位；精确执行/清理结果见本文末尾，不 push/PR/merge dev。

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

## First authorized real window: stopped at scan (2026-11-11)

- Exact source `75aec44ab38e3e7f1d6bfeb1d21e4d7cfd1d57f8`; run `25a0b29f9593455484ae3799294864c1`; context `default`, Engine `a6f010ac-61a2-4544-b133-c2b0d92d11d3`, image `sha256:7d736b679702f92a71e5721ec9fd31dce1a323cd1719b80792424913c4359525`. The coordinator authorized this one serial window and ended it after failure; no new resources or business retry are authorized.
- Actual `scan`: **102 unit, 0F/0E/0S**; sole integration identity `AssetDatabaseIT.realClamdCleanAndEicarProduceSafeTerminalStates`: **1 test, 0F/1E/0S**, phase exit1. Separate unit/focused/full phases and all five real UI flows **NOT_RUN**. The scan result is FAILED, not evidence of clean/EICAR success or signature acceptance.
- Original `scan.json`/`run.json` remain untouched under ignored `local-evidence/linux-tests/25a0b29f9593455484ae3799294864c1/`. Raw XML and raw Maven logs were deliberately not exported by the existing runner and disappeared with its writable container; no original raw failure remains on the host. Exported diagnostics contain only IllegalStateException/PSQLException, with no SQLSTATE or source frame, so the original exception chain and exact root cause remain unproved.
- Original runner `cleanup=true`; separate `local-evidence/signature/linux-window-independent-cleanup.json` confirms all six recorded full container IDs absent, all run-labelled container/network/volume lists empty, same context/Engine, and protected baseline 47 container identities/22 networks/45 volumes preserved. Coordinator independently confirmed the same six exact absences and label emptiness. No browser services or browser secret files were created; labelled build image/cache are intentionally retained.
- Diagnostic-only correction adds fixed Flyway exception classes, syntax/undefined-object/function SQLSTATE classifications, and at most four source-controlled migration version/valid-line pairs; it exports no message, SQL, location path, credentials or full stack. Fixed counts/selectors/stages/gates and old original reports are unchanged. Three new offline adversarial tests first produced 1 failure/6 subtest errors; first implementation passed 23/24 but caught a test assertion matching `.sql` inside the explicitly allowed `sqlscript` class, corrected to reject the exact SQL filename; all24 runner tests then pass. Evidence: `diagnostics-red.log`, `diagnostics-green.log`, `diagnostics-green-corrected.log`.
- Diagnostic slice full offline Python discover: **212 passed** (`diagnostics-python-all.log`, exit0). Java/UI source and fixed 102/218/82 inventory remain unchanged; no unnecessary runtime suite was rerun for this tooling-only slice.
- A scratch-only `pglast==7.22` wheel under ignored evidence reports PostgreSQL parser17.7; no project dependency changed and no database connection occurred. It parses all25 outer SQL statements but rejects the PL/pgSQL body of `app_signature_record_guard` at V26 line125; other five functions parse. In-memory parentheses around its CASE expression make the rejected function parse. This proves an offline syntax defect, not the missing original real exception; minimal source repair and parser before/after evidence are the next separate slice.
- E1/E2 remain NOT_ACCEPTED; V27 failure probe, full16/7 browser suite and A03 remain outside this window.

## Offline V26 syntax repair after failed scan

- Diagnostic slice is independent commit `cec13e335e73814a695f6ec529035865e112b282`; it changes no business source or migration. The next source slice changes only the two parentheses around the CASE expression in `app_signature_record_guard`'s meaning check (V26 line142), preserving all three exact meanings and all other authorization, snapshot, time, provider and transaction checks.
- Actual parser evidence `local-evidence/signature/v26-parser-before-after.json`: original `75aec44` V26 has25 valid outer SQL statements but fails PL/pgSQL parsing; corrected source has25 statements and all9 PL/pgSQL bodies parse; reverting only the parentheses reproduces failure and exactly matches original LF/UTF8 source hash `d10ba05c14356c6308c1ce277ffd68d4f4a4a6c98ee2fd572b2d3dc1ee0a507c`. Corrected source hash is `a03b0dc7f7e7e8187144c98554f9c4a20df3fad32cb76bc46d4af053063c6005`. Script `local-evidence/signature/check_v26_parser.py` asserts this RED/GREEN/mutation sequence.
- Parser install provenance is in ignored `parser-install-report.json`: scratch-only pglast7.22 wheel, PostgreSQL17.7 grammar, no database connection or project dependency change. Exact parser script and initial RED/isolated-function/GREEN logs remain in ignored signature evidence. This is limited syntax validation; actual PostgreSQL17.11 migration/catalog/RLS/scanner/OIDC/signature workflows still need a new coordinator-authorized resource window at the repaired SHA. No old raw runtime exception is reconstructed or asserted.
- Existing schema review confirmed referenced revision/step/attachment/asset/retest columns against the authoritative V10/V14/V18/V20/V24/V25 migrations; no role precreation, old migration edit, business assertion/count/selector/retry/skip or stage-order change was made. No extra real test or product service was started after the failed window ended.
- Repaired-source Java21 `mvn -B -ntp test`: **102/0F/0E/0S**, independently parsed Surefire method set exactly equals the frozen102 identities (`v26-repair-unit.log`, `v26-repair-unit-inventory.json`). Full Python212 GREEN belongs to the preceding diagnostic slice; frontend426/37, lint/build and browser16/7 discovery belong to the accepted unchanged frontend checkpoint, not fresh UI runtime evidence.
- `v1-v25-post-repair.json` independently verifies all25 baseline index blobs and actual checkout bytes against `git cat-file --filters` baseline output. An initial `hash-object --path` comparison incorrectly treated V13's already committed mixed line endings as a change; direct baseline/index/checkout comparison showed V13 byte-identical and the correct all25 audit passes. Git diff against58efd lists only added V26 in migration scope; diff-check, Trail build and scoped Trail check exit0.
