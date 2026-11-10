# R2：W2 通用目录树审查与集成

## 范围与仓库事实

- 分支：`r2-merge-w2`；起点 `a667343b5e66f2f6acf28f3b45bfeb1ed21235e3`，远端 `https://github.com/fangzhiy/Test365Alm.git`。
- 已执行 `git merge --no-ff b4e602d`，合并提交 `b7f4141d9275f85791de9be4c878cb6fcf7d90a9`；无冲突，Git ort 自动保留 W1 README、演示工具及其离线测试。
- 开始时 `.claude/settings.json`、`.trail/hook.cjs` 已有 Trail 自动修改，保留且不提交。
- 未操作主文件夹，未合并 dev、推送或开 PR，未启动/停止演示或手工操作 Docker 容器；集成测试只使用自己创建的 Testcontainers 临时资源。

## 审查结论

### 阻塞

- 无本轮新增的代码合并阻塞；附件本机基线仍为 2 failure/21 error，全量 Maven exit 1，不能宣称全量 GREEN（`apps/server/target/failsafe-reports`）。

### 已修

- README 现行故障注入说明从 V21/V22 修为正式 V22/探针 V23，历史 R11 轮次描述保留（`README.md` 迁移失败验证段落）。
- 新建/中断续建演示将 14 条用例与测试集分别归档到测试计划/实验室的 `OQ运行确认 / GS-VDZ50118-OQP-01-A03`，并拒绝覆盖已有人工目录，增加 3 个离线回归（`tools/demo_seed.py:111`、`tools/tests/test_demo.py:322`）。
- 已完成检查点保持只读，不自动改动正在演示的数据，使用界面显式整理（`tools/demo_seed.py:154`、`README.md:16`）。

### 建议

- 给新增 9 个目录数据库方法补确切名称的 CI 门禁，当前新增 HTTP 方法已进入 OIDC 门禁而 DB 依赖全量 Maven 发现（`.github/workflows/ci.yml`、`tools/verify_r03_oidc_http_report.py:11`）。
- 后续增加浏览器端目录筛选、移动与现有执行工作台的端到端回归，本轮只运行指定前端组件/API 测试与真实后端 HTTP 集成，不操作现有演示（`apps/web/src/FolderTree.test.tsx`、`apps/web/e2e/`）。

## 审查覆盖

- 迁移仅新增 `V22__project_folder_trees.sql`，V1—V21 无差异；工具常量、离线测试、CI、升级计数与探针编号已同步，V20→V21 的历史专项升级仍固定原目标。
- 四张目录相关表均 ENABLE/FORCE RLS，项目/租户上下文与实时成员/写成员检查齐全；幂等表主体隔离，Outbox INSERT 主体约束，runtime 仅获需要的列级 UPDATE/表级 SELECT/INSERT/DELETE，无新增 DDL/TRUNCATE 权限。
- 服务调用真实项目权限边界并设置事务局部 RLS 上下文，成员可写、VIEWER 只读；复合 FK 拒绝跨项目/跨树引用，稳定身份与不可变修订/manifest 分离。
- 创建要求幂等键并冻结重放/重验权限；修改、删除、归属移动要求具体正版本；现有 Spring 过滤器链覆盖 CSRF，目录真实 OIDC 测试包含缺 CSRF 的拒绝。
- 项目事务 advisory lock 串行化目录与归属写操作；服务和 DB 双层阻止循环及非空删除，同父同名唯一约束包含虚拟根；业务、审计、Outbox、创建幂等记录同事务，注入 Outbox 失败回滚已覆盖。
- 两个列表的目录过滤都在 SQL keyset/LIMIT 之前；省略参数保持项目全量范围，`root` 匹配无归属行及显式 null 归属；VIEWER 前端不显示写表单。
- 前端集成保留现有执行/历史/附件组件；本轮前端 224 项全部通过，尚未做真实浏览器目录验收。

## 实际检查

- Java：按 AGENTS.md 显式切到指定 Java 21，再用系统 Maven。
- `mvn -B -ntp test`：69 项通过，0 failure/error/skip。
- `mvn -B -ntp -Pintegration verify`：exit 1，162 项，139 通过、2 failure、21 error、0 skip；非附件 135 项全过，新增目录 DB 9 项及 OIDC 目录 HTTP 方法通过，耗时 8 分 25 秒。
- `npm ci`、`npm run lint`、`npm run test:run`、`npm run build`：均 exit 0；21 个测试文件、224 项通过。
- `python -m unittest discover -s tools/tests`：145 项通过；新增归档测试先 RED（缺目录），实现后 GREEN；W1 的 19 项离线测试完整保留并新增 3 项。
- `trail build` 已刷新，`trail check` exit 0，仅词项匹配提示、无冲突；最终提交前再核对。
- `git diff --check`（本轮交付文件）：通过。

### 与 W2 实际 XML 基线逐项对比

只读读取 `../w2-folder-tree/apps/server/target/failsafe-reports/TEST-*.xml`（W2 HEAD `b4e602d`），与本分支全部 22 个套件的计数、162 个方法的 passed/failure/error/skipped 状态精确比较：完全一致，差异集合为空，比较脚本 exit 0。脱敏结果保存在 `local-evidence/r2/integration-comparison.json`；没有读取 XML 环境属性或复制异常正文。

| 套件 | tests | failure | error | skip | 与 W2 比较 |
|---|---:|---:|---:|---:|---|
| AssetDatabaseIT | 16 | 1 | 12 | 0 | 方法及状态相同 |
| AssetMigrationUpgradeIT | 1 | 0 | 1 | 0 | 方法及状态相同 |
| AssetProvisioningUpgradeIT | 2 | 0 | 1 | 0 | 方法及状态相同 |
| AssetTerminalOutcomeIT | 5 | 0 | 5 | 0 | 方法及状态相同 |
| OidcCallbackSecurityIT | 25 | 1 | 2 | 0 | 附件三方法相同，其余 22 项通过 |
| 其余 17 个套件 | 113 | 0 | 0 | 0 | 全部方法通过且与 W2 相同 |

两项 failure 分别为 `AssetDatabaseIT.publishedAssetAllocatorAndCurrentPointerCannotMoveBackwardOrGap`、`OidcCallbackSecurityIT.realOidcAssetHttpTerminalCompleteFreezesRejectionAndReauthorizesReplay`。OIDC 两项 error 分别为 `realOidcAssetHttpTypedLinksFrozenVersionsAndLiveDownloadRevocation`、`realOidcAssetHttpIntegrityCsrfRolesAndIdempotency`；因此实际是三条附件 HTTP 方法非通过，任务文字的“两条”与 W2 报告不一致。其余 19 项 error 均属于表中四个 Asset 套件，逐方法状态也与 W2 完全一致，完整方法清单保存在脱敏 JSON。

## 下一步与注意事项

`git merge-base --is-ancestor dev HEAD` exit 0，当前 dev 可快进至本分支；接受已知附件基线的前提下可集成，没有新增失败或审查阻塞，但不是全量 GREEN。合并后总管按仓库约定删除本交接文件并更新路线图第 7 节，本 worker 不操作 dev。已有运行中的演示没有自动归档，完整检查点不会触发新增目录写入；不要删除检查点强制重播。日志与报告仅在忽略的 `local-evidence/r2/` 及 `apps/server/target/`，不提交包含运行环境信息的原始日志。
