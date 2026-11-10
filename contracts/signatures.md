# 模拟签署与记录锁定（E1/E2）

基线：总管验收并集成的 `58efd4788596a06a3205603edabadda04ffa58dc`（C/D）。正式迁移仅追加 V26，故意失败探针 V27；V1..V25 不改。E1/E2 未验收，当前仅离线实施，不启动真实资源。

## 三种固定含义

| kind / meaning | objectId / snapshotId | 新签署前置及签署人 |
|---|---|---|
| TEST_APPROVAL / 用例批准确认 | testCaseId / revisionId | 当前封存修订的最新复核状态 APPROVED；必须为该修订指定的当前有效项目 writer，admin 不绕过 |
| RUN_COMPLETION / 运行完成确认 | runId / attemptId | 确切 attempt 已 FINISHED（现有执行终态）；当前有效项目 writer |
| DEVIATION_CLOSURE / 偏差关闭确认 | defectId / revisionId | 当前偏差修订 CLOSED；当前有效项目 writer；原关闭业务前置由正式缺陷服务验证 |

不增加禁止自复核或不同人签名规则。签署确认已有状态，不替代复核、完成或关闭命令。客户端不能提交姓名、密码、token、含义、状态、时间或摘要。

## 再认证

复用已有 Spring Security OIDC authorization-code + PKCE/state/nonce、真实 Keycloak 和同源 CSRF。签署提供方与身份验证分开：`SignatureProvider` 只接收服务端核验后的冻结签署材料，默认 `SIMULATED_V1` 明确模拟，无真实厂商/法律效力/合规认证声明。

受 CSRF 保护的 POST 创建一次性挑战，绑定当前服务端 OIDC issuer+subject、本地主体、项目、目标及确切版本/摘要、会话内随机绑定值，5 分钟失效。挑战不是 bearer 授权。认证跳转必须由同一有效会话持有挑战，并向现有 IdP 请求 `prompt=login`、`max_age=0`。只接受本次授权码回调的服务器验证 ID token；issuer+subject 必须与原会话完全一致，`auth_time` 必须不早于挑战创建秒、不晚于服务端时间容差，且最终提交时不超过 2 分钟。缺少/旧 auth_time、换身份、错误 state/nonce、伪造/跨会话/过期挑战均拒绝；不接受前端宣称已认证。密码仅输入 IdP 页面，应用不接收或持久化密码，不增加 direct password grant。

再认证失败不形成签署。其他主体登录成功也不能签原主体；身份不符使本应用会话失效，要求重新登录。回调重查主体停用状态和项目写权限，最终签署及幂等重放也重新核验当前授权。原会话失效/退出无法使用挑战。OIDC 原 token 仅维持现有服务端会话，绝不进入签署、审计、Outbox、幂等 JSON 或响应。

规范依据：OpenID Connect Core 1.0 §3.1.2.1 (`prompt`, `max_age`)、§3.1.3.7 (ID token validation)，Spring Security OAuth2 Login Advanced Configuration / Authorization Endpoint。复用现有注册、回调地址与验证链，不新开公开凭据接口。

## API 与持久记录

路径前缀 `/api/v1/projects/{projectId}/signatures/{kind}/{objectId}/{snapshotId}`：

- GET 返回该确切快照的签署记录与服务端可签能力；先项目权限/RLS，最多一个不可变签署。
- POST `/challenges`，body `{}`，强 `If-Match`（用例/偏差共享 rowVersion；运行使用 attempt rowVersion）、持久 `Idempotency-Key`；冻结目标及身份，返回无秘密挑战 ID、到期时间及认证 URL。
- GET `/challenges/{challengeId}/authenticate`，再次核验同一会话/身份/权限，跳转原 OIDC 入口。回调以固定 webOrigin 返回有限成功/失败状态，不含 token/身份/证据秘密。
- POST，body `{challengeId}`，同样强版本及持久幂等键，认证后显式确认签署。服务端重读前置/摘要，签署、挑战消费、共享版本增量、审计、Outbox、冻结幂等响应同事务。

创建挑战不改业务版本。新签署递增用例/偏差 rowVersion；已完成 attempt 的终态数据不变，独立唯一键 `(tenant,project,kind,object,snapshot)` 排斥重复签署。运行版本条件验证 attempt 的终态版本；不为签署修改终态 attempt。挑战含自己的 expectedVersion，客户端必须相等。任何目标/版本/摘要变化要求重新创建挑战；未知结果保留原 body/etag/key 重试，不能自动换键。

同键同意图在有效幂等期返回冻结响应，重放前重新核验当前主体/项目及指定复核人资格；成功后挑战已消费不阻止其原命令重放，新键复用已消费挑战拒绝。签署 record 包含 schema、固定含义、项目、object/snapshot 身份、完整规范化内容快照及 SHA-256、服务端冻结主体 issuer/subject/姓名、本地主体 ID、服务端签署时间、OIDC 再认证时间、明确模拟 provider ID 与非秘密 evidence。任何历史签署不可 UPDATE/DELETE/TRUNCATE；有外键/范围/RLS/不可变触发器及唯一约束。

签署快照包括：用例封存内容/业务字段/有序步骤及确切 APPROVE 事件；运行 manifest、确切 attempt/结果/执行时间/步骤和冻结附件身份；偏差确切修订/来源/关闭状态及关闭复测绑定。摘要在服务端生成，JSON 属性按确定顺序，数组保持权威顺序；不从最新对象重建历史。

## 锁定与 UI

用例旧修订/步骤、完成 attempt/manifest/结果和签署均保持只读；用例编辑追加新修订，独立从 DRAFT 重新复核/签署。重跑创建新 attempt，历史签署不覆盖新版。已签 CLOSED 偏差的原修订保持只读，后续变更只能通过正式追加修订流程，新的非 CLOSED 修订不可签；已有 CLOSED 终态业务规则继续有效。附件不得在已完成 attempt 新增，签署冻结旧链接。

UI 明示“模拟电子签署”，展示三种固定含义、确切快照与摘要、主体/时间/模拟证据。再认证在真实 IdP 页面；成功后回到原签署面板显式提交。挑战及未知签署意图锁定父级相关编辑和导航，401/403/404 或真实身份/项目 teardown 清理范围；迟到响应不能写入其他范围。历史读取/后续报告由授权接口读取原记录，F 报告不在本任务实现。

## 验证边界

纯规则与前端先 RED/GREEN；真实 PostgreSQL、非空 V25→V26、真实 OIDC/CSRF、并发/事务回滚/旧只读/重放重新授权/跨范围/伪造挑战须单独验证。固定原95单元/202IT/66专项方法集合零删除，新方法逐名固定；离线通过不能宣称 E1/E2 完成。真实错误密码、其他身份、正确身份再认证 UI 在总管批准的 owned 独立环境执行，单 worker、零 retry/skip，真实扫描和双 SCRAM 门槛不降，资源精确清理。
