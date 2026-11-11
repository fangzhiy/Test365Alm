# 验收证据与历史限制索引

本文件供接手定位使用。具体断言、历史失败和总管决定以各 [reviews](../reviews/) 文档为准。实际源、最终worker文档源和总管发布源分别记录，不合并成一个“全部测试源”。

## 已入库的长期记录

| 工作线 | 总管验收 | 原worker交接归档 |
|---|---|---|
| A审计 | [audit-history-coordinator-review.md](../reviews/audit-history-coordinator-review.md) | [audit-history-worker-handoff.md](../reviews/audit-history-worker-handoff.md) |
| B业务字段 | [business-fields-coordinator-review.md](../reviews/business-fields-coordinator-review.md) | [business-fields-worker-handoff.md](../reviews/business-fields-worker-handoff.md) |
| C执行 | [execution-grid-coordinator-review.md](../reviews/execution-grid-coordinator-review.md) | [execution-grid-worker-handoff.md](../reviews/execution-grid-worker-handoff.md) |
| D复核 | [test-case-review-coordinator-review.md](../reviews/test-case-review-coordinator-review.md) | [test-case-review-worker-handoff.md](../reviews/test-case-review-worker-handoff.md) |
| E签署 | [signature-coordinator-review.md](../reviews/signature-coordinator-review.md) | [signature-worker-handoff.md](../reviews/signature-worker-handoff.md) |

任务状态见 [oq-delivery-todo.md](../../tasks/oq-delivery-todo.md)，交付范围见 [oq-delivery-plan.md](../oq-delivery-plan.md)，阶段状态见 [路线图第7节](../alm-replacement-roadmap.md#7-阶段状态)。模拟签署的公共契约见 [signatures.md](../../contracts/signatures.md)。

## 各阶段实际结果

| 工作线 | 实际后端源 | 单元/完整IT | 实际UI源及完整业务流数量 |
|---|---|---|---|
| A | 34c19f6 | 85/167 | 4a13589，3条 |
| B | da9906c | 89/175 | b5cc1b2，3条 |
| C | 96def34 | 93/187 | 8f48057，3条 |
| D | 6fa15ce | 95/202 | afa0f4a，4条 |
| E | c549b25 | 102/218 | 3af82c4，5条 |

各行是各自时间点的真实验收，不是五套互不重叠的测试，不能相加声称覆盖更多独立方法。最新E组合保留既有方法，后端阶段为scan102/1→unit102→focused102/82→full102/218，全部逐名零F/E/S。

## E本机证据位置

下面路径相对 `C:/Users/22538/orca/workspaces/Test365Alm/feat-signature`。local-evidence被Git忽略，新的clone不会带上这些文件；需要在原机器保留worktree或另行做脱敏证据移交，不能从仅有Git源码推断它们仍存在。

| 相对位置 | 用途 |
|---|---|
| local-evidence/linux-tests/8eef9cc7836a4a6698d84fae4587db2f/ | 实际c549后端四阶段报告及runner结果 |
| local-evidence/local-browser/78aa3de77a98483392c8f61b6a21043c/run.json | 实际3af82五UI、资产预检、scanner和runner清理结果 |
| 同目录results.json | 五个确切完整身份、expected/status/retry与worker配置 |
| 同目录ownership-start.json、manifest.json | 业务前完整资源身份、run/source/project和context/Engine |
| local-evidence/signature/e3af82-final-window-review.json | 最终交接SHA、14文件SHA-256、实际源与复用后端边界 |
| local-evidence/signature/e3af82-browser-start-independent.json | 业务前独立live归属核验 |
| local-evidence/signature/e3af82-browser-independent-cleanup.json | 独立资源/秘密/进程清理和保护基线 |
| local-evidence/signature/e3af82-browser-process-history.json | 37个PID+Created身份历史 |
| local-evidence/signature/e3af82-browser-process-final.json | 最终进程独立核验 |
| local-evidence/signature/e3af82-bounded-signature-timeline.json | 有界脱敏业务时间线；不含密码、Cookie或token |
| local-evidence/signature/c549-linux-window-independent-cleanup-verified.json | 62完整后端容器ID的严格不存在核验 |

总管自己的独立证据位于 `C:/Users/22538/orca/workspaces/Test365Alm/integrate-audit/local-evidence/e-final-coordinator-cleanup.json`。worker和总管记录各自保留，不用一份覆盖另一份。发布前索引14份哈希已复核一致；校验以索引记录的字节为准，Windows checkout行尾与Git blob行尾可能不同。

## 不得丢失的失败与限制

| 检查点 | 实际事实 | 后续结论边界 |
|---|---|---|
| 75aec44 / run25a0b29f9593455484ae3799294864c1 | 102unit通过，扫描IT ERROR，后续及UI未运行；已清理 | 原raw XML/完整异常链未导出，不能重建。离线解析发现V26 CASE括号问题，c549在真实Linux四阶段通过 |
| c549 / UI f554465384d446d8be1c8b8206d3bb00 | 2P3F，真实资产及scanner通过，已清理 | remembered-subject密码表单与“批准本修订”按钮分别修复 |
| 7881 / UI dae446c672c2418d860ba1959a5db57c | 4P1F，错误密码已真实拒绝，旧错误节点locator失败，已清理 | 历史snapshot没有节点ID；静态模板和RED/GREEN证明新locator，3af82完整五条重新执行全过 |
| 首份Linux独立cleanup审计 | daemon not-found格式识别导致false，原JSON保留 | 后续只读严格62完整ID核验通过，没有资源删除重试 |
| 累计Trail词语冲突 | 原exit1及JSON保留，具体误报已逐项写入总管review和集成提交 | 不改旧learning、不把原exit1重写为通过；总管文档归档增量另有无冲突检查 |

后端c549到实际UI3af82及最终交接的后端/tools/CI/contracts树一致，因此总管允许复用后端证据；它不是后续提交的后端重跑。离线pglast/PG17.7纯解析不替代真实PG17.11/catalog/RLS/OIDC；静态Keycloak模板不构成历史DOM截图。

## 待恢复后另验收

V27独立故障探针、完整16UI全集、F报告、真实授权A03十四用例及业务报告格式认可均需各自实际证据。模拟签署的技术通过不代表外部电子签名厂商认证或法规符合性结论。新的GitHub CI结果必须按确切commit/run记录，不能把GitHub上传成功当作CI通过。
