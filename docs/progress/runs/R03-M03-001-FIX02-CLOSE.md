# R03-M03-001-FIX02-CLOSE — A 段回归证据收口

## 任务与起点

- 任务：`R03-M03-002` A 段，补正 FIX02 的客户端复用和主体基线证据；B 段在 A 门禁通过后另建分支。
- 仓库：<https://github.com/fangzhiy/Test365Alm.git>
- 起始分支：`feat/r03-m03-001`，起始 SHA：`6489932ebd93542f80a036709c58e3fdab06082e`。
- PR #2 仍 Open，base 为 `feat/r02-m02-001`；PR #1 仍未合并。
- 本记录不改写 `R03-M03-001-FIX02.md` 中的历史限制和旧 G04/G05 结论。

## A 段修改

代码提交：`75b9d9304ceff2c38c40d3c9d9be96957db88145`。

仅修改 `apps/server/src/test/java/com/test365alm/server/identity/OidcCallbackSecurityIT.java`：

- 失败授权和随后合法授权使用同一个 `ClientFixture`，其中包含同一个 `HttpClient` 和 `CookieManager`；测试通过对象身份断言两次流未替换客户端或 Cookie 容器。
- 每个五类非法 token 变体先由独立 owner 连接写入两个确定的非空启用哨兵主体及一个启用的 existing-target 主体；另运行 absent-target 流。
- 对每个流用 owner 连接读取完整 `principal` 快照，比较 `id`、`issuer`、`subject`、`display_name`、`disabled_at`、`created_at`、`updated_at`；wrong-issuer 场景按 token 的 issuer+subject 键验证。
- 保留原七个 JUnit 方法名，兼容严格 HTTP 报告门槛；五个非法方法内部各覆盖 absent/existing 两个子场景。

## A 验证

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| Java test compile | PASS | `mvn -B -ntp -DskipTests test-compile -Dmaven.repo.local=...\\.maven-repo`，exit 0；编译目标 release 17。 |
| 本机真实 Testcontainers OIDC | BLOCKED | 同一命令实际运行 `-Pintegration -Dit.test=OidcCallbackSecurityIT verify` 时，Docker Desktop named pipe 返回 `AccessDeniedException`，Testcontainers 无可用 Docker；未把该次失败写成通过。 |
| HTTP 报告门槛 | PASS（CI） | server job 在 Push CI 中实际执行，报告保持 7 tests、0 failures、0 errors、0 skipped。 |
| Push CI | PASS | [run 36373203011](https://github.com/fangzhiy/Test365Alm/actions/runs/36373203011)，checkout/head `75b9d9304ceff2c38c40d3c9d9be96957db88145`，六 job success。 |
| PR CI | PASS | [run 36373206437](https://github.com/fangzhiy/Test365Alm/actions/runs/36373206437)，PR merge checkout 由 GitHub 生成，六 job success。 |

CI 实际运行七个 JUnit 方法；A 的非法 token 子场景为 `5 × 2 = 10`，另有合法对照及失败后合法恢复。没有用 mock 登录替代真实应用 HTTP 回调。

## A 门禁结论

**A PASS（以远端 Push/PR 六 job 为门禁）**。旧 FIX02 的 G04/G05 在本记录之前不作为本轮通过依据；本提交和对应 CI 重新提供了客户端/Cookie 复用及每个非法变体的非空数据库基线证据。

随后才允许创建 B 段分支；B 不写入 PR #2 的分支提交。

## 未完成与限制

- 本记录不实现项目、成员、角色、撤权、RLS 或数据隔离闭环；这些属于 B 段。
- 当前工作站 Docker named pipe 权限阻断了本机 Testcontainers 复跑；CI 使用隔离 PostgreSQL 并已通过。
- Java 21/Node 24 的目标运行时由 CI 验证，本机仍为 Java 17/Node 26；历史记录保持不变。
