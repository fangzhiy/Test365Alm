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
