# ADR 010 — R03 本地 OIDC 与单节点 BFF 会话

状态：本轮已实现、待 CI/外部审核。依赖 ADR 006、008、009；不改变 R02 的健康接口。

本轮使用 Spring Boot 4.1.1 管理的 Spring Security OAuth2 Client，OIDC Authorization Code 和 public client PKCE S256；开发/CI 身份提供方为 `quay.io/keycloak/keycloak:26.4.4@sha256:c6459d5fae1b759f5d667ebdc6237ab3121379c3494e213898569014ede1846d` 临时容器（本机 `docker buildx imagetools inspect` 核验）。浏览器通过同源 Vite 代理访问后端；注册回调固定为 `http://127.0.0.1:5173/login/oauth2/code/test365alm`。服务端验证的 issuer 与 subject 才能查找或创建本地 `principal`；同邮箱、同名不合并。没有 OIDC 配置时，受保护 API 拒绝访问。

浏览器仅持有 Spring Security 单节点 HttpSession Cookie（HttpOnly、SameSite=Lax、Path=/）；Spring Security 登录时轮换会话 ID。会话闲置 30 分钟过期；进程重启需重新登录，没有多副本会话共享。本地 HTTP 只允许回环开发；生产配置必须 HTTPS、Secure Cookie 与正式 IdP。后端存 OAuth 凭据，不向前端返回 token。`POST /api/v1/auth/logout` 只销毁本地应用会话，不承诺 IdP 全局退出。已持有会话的本地主体停用，在下一次受保护请求时拒绝并注销会话；IdP 断线时已建立的本地会话按此本地状态继续工作，不声称 IdP 实时撤权。

迁移使用独立账号；运行账号非 owner、非 superuser、无 BYPASSRLS，仅有探针读取和 principal 必需 DML。项目授权、RLS、集群会话、MFA 和完整审计留待后续。正式迁移 V2 之后，R02 测试故意失败迁移改为 V3，且检查专属 SQL 错误标记。

本机 `mvn dependency:tree` 实测 Boot 4.1.1 BOM 解析为 Spring Security 7.1.1；本机 Java 17/Node 26 是目标 Java 21/Node 24 的暂时偏差，后者继续由 CI 核对。public client 无客户端密钥，Spring Security 在 `client-authentication-method=none` 时自动使用 PKCE；Keycloak 测试 realm 另要求 S256。正式环境应改为经安全审查的机密客户端/凭据策略、HTTPS、Secure Cookie 和独立身份系统，不能复制 `start-dev`。

实现依据：[Spring Security OAuth2 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html)、[public client PKCE](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html)、[CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)、[Keycloak 容器](https://www.keycloak.org/server/containers)。
