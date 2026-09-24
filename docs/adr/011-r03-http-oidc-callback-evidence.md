# ADR 011 — R03 HTTP OIDC 回调与主体不变证据

状态：FIX02 实现，待本轮远端 CI/外部审核；不改变生产认证策略。

## 决策

为补齐 E05/F03，使用 `OidcCallbackSecurityIT` 通过真实 HTTP 完成同一条应用授权入口、授权回调、token 兑换、JWKS 验签、OIDC 用户加载和 `/api/v1/me` 请求。测试 IdP 是测试类内绑定到回环随机端口的本地协议服务，数据库是 Testcontainers 创建的 PostgreSQL 17.11 固定摘要临时容器；测试不读取日常 `.env`，不调用 `authenticate()`、`oidcLogin()` 或替换生产过滤器链。

每个场景拥有独立 Cookie 容器、一次性授权码和合成 subject。token 端点实际校验 redirect URI、一次性 code、PKCE S256、client id 和 grant type，并记录成功兑换；五个非法 token 只改变一个目标条件（错误签名、issuer、audience、过期或 nonce）。回调失败后，用同一 Cookie 请求 `/api/v1/me`，并通过 owner 连接读取 principal 的完整字段快照（包括 ID、issuer、subject、显示名、停用时间和创建/更新时间）确认没有新增或更新。合法场景和失败后重新授权的合法场景作为对照。

## 取舍与边界

Spring Security 的 OIDC resolver 将哈希后的 nonce 放入授权请求参数，并保留原始值用于校验；测试从实际授权重定向读取该参数并在合法 ID Token 中回显，避免硬编码或二次散列。测试 IdP 仅用于协议回归，不声称替代 Keycloak 浏览器、所有 OIDC 威胁模型或生产机构认证。测试报告由 `verify_r03_oidc_http_report.py` 严格要求七个实际用例、零失败/错误/跳过；报告和日志不上传 token、Cookie、私钥或本地秘密。
