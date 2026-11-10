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
