# 模拟签署与记录锁定总管验收

2026-11-11。总管在 `msg_088209b2a237` 批准 E1/E2。最终 worker 交接为 `6c7b117070b18130c8cbfa78b5e3cfb1bbdb1c73`；相对实际 UI 源 `3af82c48b97eb4eb01d1b61ea6e9db68fce2f082` 仅交接文档变化。前置 D 基线为 `58efd4788596a06a3205603edabadda04ffa58dc`。本次将已验收成果合入 dev，按用户最新要求推送 GitHub 主分支 `main`（设为默认），并同步远端 `dev`；F/G 继续暂停。

## 实测源与证据

证据保留在 feat-signature 工作区忽略的 local-evidence 内；不提交原始日志、快照环境或秘密。总管已独立调用四阶段固定 valid_report、五结果 validate_results，并核验实际源码树和清理。

| 验证 | 实际源和 run | 结果 |
|---|---|---|
| Linux clean/EICAR | c549b25dd962554607ca10dc869326371c86b8c1 / 8eef9cc7836a4a6698d84fae4587db2f | 102 单元及 1 确切真实扫描方法，零 F/E/S |
| 单元 | 同上 | 102 确切方法，零 F/E/S |
| 固定专项 | 同上 | 102 单元、82 集成（原66+E16），零 F/E/S |
| 唯一完整集成 | 同上 | 102 单元、218 集成，逐名闭集与每 case 零 F/E/S |
| 完整五条真实 Keycloak UI | 3af82c48b97eb4eb01d1b61ea6e9db68fce2f082 / r03-local-78aa3de77a98483392c8f61b6a21043c | 五个身份均 passed/expected passed/retry0，workers1/retries0/skip0，exit0 |

五条确切 UI 身份：

- `real Keycloak UI manual test case flow creates steps, edits order, and keeps viewer read-only`
- `real Keycloak UI designated reviewer rejects approves and new revision requires fresh review`
- `real Keycloak UI manual execution creates, resumes, finishes and reruns a two-step run`
- `real Keycloak UI member creates defect from FAIL retests PASS closes and preserves history`
- `real Keycloak UI simulated signature rejects wrong credentials binds identity and preserves signed revision`

真实私有 S3/ClamAV clean/EICAR preflight 和正式 scanner 双独立 SCRAM 连接均 PASSED。原阶段报告在 `local-evidence/linux-tests/8eef9cc7836a4a6698d84fae4587db2f/`，原浏览器报告在 `local-evidence/local-browser/78aa3de77a98483392c8f61b6a21043c/`。最终索引 `local-evidence/signature/e3af82-final-window-review.json` 区分实际源和最终交接，14 份文件 SHA-256 已逐项匹配。

c549b25 至 3af82c4 及最终交接的 apps/server、tools、.github、contracts Git 树相同，故获准沿用 c549 后端实测；没有声称后续 SHA 重跑后端。离线最终 449 前端/40 文件通过，lint/build 通过、8 个既有 warning；212 Python 属于此前未变工具检查点。Playwright 16 条/7 文件仅发现，不能当作全集执行。

## 契约与审查结论

- 提供方接口可替换，当前明确标识 SIMULATED_V1。TEST_APPROVAL、RUN_COMPLETION、DEVIATION_CLOSURE 绑定确切不可变修订/attempt、含义、主体、摘要和服务端时间。
- 再认证使用真实 IdP 弹窗，密码仅进入 IdP；服务端校验 issuer/sub/auth_time/state，错误密码无确认，其他主体不能签署。当前权限、RLS、强版本及持久幂等重放均重新授权，admin 不绕过指定有效 writer。
- 用例需当前 APPROVED 修订；运行 FINISHED attempt 与 CLOSED 偏差签署独立唯一记录，不增加终态业务版本。签署、审计、Outbox、冻结响应同事务；并发挑战/修订、审计及 Outbox 故障回滚有实际 DB 证明。
- 历史签后只读，新修订需重新复核和签署，新 attempt 独立。未知/in-flight 意图保留原 body、ETag、幂等键；迟到目录回调先检查同步 scope 锁，真正项目/身份/权限/reset 和拒绝访问仍 teardown 并隔离迟到响应。
- 正式新增 V26，V1..V25 blob 逐字相同；非空 V25→V26 升级、旧表原列及冻结重放实际验证。原95/202/66库存零删除；E16为12DB、1升级、3真实OIDC，六Job和原门禁保留。

## 总管独立清理

原后端 cleanup=true；总管已同 default context / Engine `a6f010ac-61a2-4544-b133-c2b0d92d11d3` 独立确认 62 个捕获完整容器 ID 均不存在、run 三类标签空。

浏览器原 cleanup=true、processes_stopped=true。业务前 ownership-start 与独立 live inspection 保存 4 完整容器 ID/Created/Image、1 完整网络 ID/Created、3 确切卷名及 run/source/project 标签。总管同 desktop-linux、同 Engine 逐项严格 not-found，run/project 三类标签空；三秘密文件删除，37 个 PID+Created 均消失，同 snapshot root Java/Node0。保护47既有容器身份/StartedAt、22网络、45卷不变。

总管证据 `integrate-audit/local-evidence/e-final-coordinator-cleanup.json`，worker独立证据 `local-evidence/signature/e3af82-browser-independent-cleanup.json`。没有删除未知资源、触碰 W1、日常 env 或更改 Defender。

## 历史失败及限制

75aec44/run25a0b29 的 scan 为102单元通过、1扫描IT ERROR，后续 NOT_RUN。原 raw XML/完整异常链未导出且容器已清理，此缺口保留。离线 pglast/PG17.7 纯解析证明 V26 PLpgSQL IF 内 CASE 缺括号，修复及撤括号变异成立；不能替代真实 PG17.11 验收。c549 后端四阶段实际全绿。诊断增强独立提交只增加白名单类别/SQLSTATE和有界迁移位置。

c549/runf5544653 五UI为2P3F，分别修复 remembered-subject 仅密码表单和实际“批准本修订”按钮；7881/rundae446c6 为4P1F，错误密码已被真实拒绝，但旧错误节点选择器不符。现存确切 Keycloak 镜像只读静态模板与 RED/GREEN 证明 base/v2 结构，最终选择器要求已知错误ID、polite live关系及精确拒绝文案；原 visible/no-confirm 和所有业务断言保留，无增加超时、sleep、retry或skip。历史 snapshot 无节点ID证据，不声称重建历史DOM。

首份 Linux 独立清理审计因 daemon not-found 前缀格式误判 false，原件保留；之后仅只读精确复核通过，无删除重试。旧 2P3F、4P1F、RED/编译夹具错误及旧 Trail exit1 全保留。

合流前 `trail check --base e21c9b4 --json` 检查61文件，产生9项 conflict、11项 lead、无重叠；逐项核对冲突为词语误报，保留原JSON并在提交说明解释：

1. OIDC新签署方法断言模拟提供方/身份，不更改旧步骤 executedAt，也不从文本保存时间补历史时间。
2. 新真实签署UI保留精确 reviewer combobox，未恢复 label 歧义；五流程实际通过。
3. 新流程使用 isOwnedRun，合法 local/CI-owned 才执行；严格五身份门禁拒绝 skip，没有恢复旧 CI-only 错误。
4. DefectDetail增加签署锁，原 auditRefresh 在写入/复测成功后递增及历史key保留，未改为只靠 rowVersion。
5. 同一 DefectDetail 的复测历史刷新保留，未删除独立 auditRefresh；两条历史learning均继续满足。
6. TestCasesPanel保留原ReviewSession、同步scope锁、load/selectTest守卫与父组件回归，签署沿用此锁；没有回到仅ReviewPanel回归。
7. TestExecutionPanel历史key仍含run/attempt/attempt版本及每步版本，新签署版本参数不代替审计刷新key。
8. linux_test_inside增加E16闭集，保留真实Linux clean/EICAR及102/82/218零F/E/S门禁，没有mock或降低库存。
9. runner回归冻结原95/202/66逐名哈希，无角色升级方法及全部方法保留，没有预建角色掩盖迁移故障。

其余 lead 属新增learning的候选词语提示，不构成新增事实阻塞；旧 learning 均未编辑。总管文档归档增量另做 scoped Trail 和 diff 检查。

## 收尾边界

worker交接原文字节相同归档为 `signature-worker-handoff.md`，删除功能handoff，更新E1/E2、计划、路线图第7节和总管工作台。仅接受模拟签署/锁定切片；外部厂商/合规认证、部署、F报告、G/A03不在本次验收。V27独立故障探针及完整16UI全集仍 NOT_RUN。用户暂停后仅恢复本次收尾与 GitHub 默认主分支推送，F/G 不启动。
