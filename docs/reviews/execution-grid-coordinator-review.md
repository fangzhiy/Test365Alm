# 执行网格与手工运行器总管复核

2026-11-11。批准 C1/C2 本地 dev 集成：后端确切源 `96def34c6c6928f42ed4370d90b8c179501f2b48`，最终浏览器源 `8f48057d8b2a460d4b7dc2a9b71d5b75be15deec`，worker 最终交接 `ba65160d247a4b55b9aa6bbab5a723baeabc9346`。前置已验收 dev 为 `23d138cda8fd6d34ec598064778b42b121175109`；它是最终交接的祖先。

## 实际证据与源码适用范围

证据保存在 `feat-execution-grid` 工作区忽略的 `local-evidence/`。总管独立读取汇总与逐方法状态，并按固定库存比较集合，不只比较总数。

| 阶段 | 确切源与运行 | 实际结果 |
|---|---|---|
| 真实 clean/EICAR 预检 | 96def34 / d3142b4157bf457ca66da0171cc4495e | 93 单元、1 真实扫描，零 F/E/S、exit0 |
| 单元 | 同上 | 93 个唯一方法，零 F/E/S、exit0 |
| 固定专项 | 同上 | 93 单元、51 个确切集成方法，零 F/E/S、exit0 |
| 唯一完整集成 | 同上 | 93 单元、187 个确切集成方法，零 F/E/S、exit0 |
| 三条真实 Keycloak UI | 8f48057 / r03-local-7dfd6f17387f4be4ab8e373d774f0fa9 | 用例、执行、缺陷均 passed；workers1/retries0，每条 retry0，无 skip，exit0 |

后端报告在 `local-evidence/linux-tests/d3142b4157bf457ca66da0171cc4495e/`；浏览器报告、manifest、归属、清理和有限 UI 时间线在 `local-evidence/local-browser/7dfd6f17387f4be4ab8e373d774f0fa9/`。浏览器明确 `assets=real-owned`，真实私有 S3/ClamAV preflight 与正式 scanner provision（两次独立 SCRAM）均 PASSED。

96def34 之后仅正式 provision/本地浏览器设施、执行面板详情读取锁及两处浏览器选择器变化；`apps/server` 树完全相同。279 前端/190 Python 是各修复检查点实际执行结果；最终仅脚本定位变化没有冒充再次运行全套。总管另独立通过 29 项 runner/固定门禁、28 项资产/provision/资源守卫、执行面板 37 项回归。最终 ba65160 相对 8f48057 只改契约和交接文档；本地集成不改被测运行源码。没有为文档重跑后端，也没有把 d3142b41 写成后续提交重新执行的 187 项。

## 独立清理核验

后端 `cleanup=true`、`cleanup_events=[]`。总管在 `default` context、Engine `a6f010ac-61a2-4544-b133-c2b0d92d11d3` 独立核对：53 个捕获完整容器 ID 均不存在，同 run 标签的容器、网络、卷为空。

浏览器 `cleanup=true`、`processes_stopped=true`。总管在 `desktop-linux`、同一 Engine 独立核对：4 个完整容器 ID、1 个完整网络 ID、3 个确切卷名均不存在，同 project/run 标签的容器、网络、卷均为空。三份生成秘密 `.env.r03`、`local-evidence/r03/realm.json`、`local-evidence/local-browser-compose.json` 均不存在；同快照 root 的 Java/Node 进程为 0。保留 W1 和其他资源，没有全局清理或更改 Defender。

## 已复核的契约与修复

- 网格在 LIMIT 前做目录/实例过滤，保留不透明游标，打开确切 run。负责人使用当前有效项目 writer 目录与独立版本；未知结果保持原意图、版本和幂等键。新运行冻结负责人身份/姓名，新 manifest 格式为 3，旧 manifest/hash 与重放不重算。
- 实际文本和结论独立，NOT_RUN 可有文本、PASS 可空、FAIL/BLOCKED 仍需文本。首次服务端 NOT_RUN 转为已有结论时记录 executedAt；保存时间独立。历史未知时间保持 null，不从 updatedAt 伪造执行时间。
- 步骤附件绑定 scoped runStepId 和确切 RUNNING attempt，暂停/完成拒绝新链接；重跑使用新步骤身份，历史证据仍按授权读取。保持真实扫描、不可变链接、RLS、强版本与审计/Outbox 同事务。
- 共享升级保留 B 全部旧表原列快照、C 执行三表新增列投影、旧 hash/冻结重放。历史内容/执行/DefectEvidence 仅在旧目标上下文显式注册固定 61711294 实现，latest 仍验正式 runtime。总管机械比对过固定 prepare/helper，未删原断言。
- V24 两处授权改为角色存在时授予原最小权限，PUBLIC 仍撤权，不创建 runtime 角色。PrincipalUpgradeIT、PlatformDatabaseIsolationIT 两个无角色方法在最终 full 实际通过；旧 V1..V23 未改。
- 清理只接纳完整精确 ID、exit1 和完整 not-found 响应，并同 context/Engine 重新确认不存在；其他错误、替换、重启、归属变化仍拒绝。首次非零 StartedAt 固定，无重试删除。确定性竞态回归通过，未把旧 cleanup=false 的未知原因追认为该竞态。
- 正式 provision 子进程仅恢复 Docker 配置查找所需 profile 变量，不转发给 PG；只读探针确认故障与修复。新建一次性 PG 显式 SCRAM，不改已有 HBA。执行面板使用既有 pending 锁覆盖详情四项读取，只对当前 round 释放，保留超时/迟到响应保护。两处负责人脚本改为精确 combobox，真实 DOM/Edge 证实原 label 同时命中 FORM/SELECT；业务断言不变。

## 保留的失败与检查限制

- df72d4f / ca55b950：专项 1F2E，full/browser 未运行；22 个容器清理确认。
- fa369c25 / e7cd9b1f：专项通过，full185P/2E；原 cleanup=false 不覆盖，独立 53/53 不存在证据保留。
- 96def34 / 960cb250 浏览器准备：preflight 通过，provision 失败，三 UI 未运行。
- 9d19959 / 491f4279：1P2F；d4c1f2a / 5d2d3971：2P1F。原报告及清理证据保留，最终绿色来自新确切源完整执行。

总管合流前 `trail check` 返回 4 处冲突，并有 400KB diff 扫描上限，不能声称全 diff 已扫描；各修复已有增量检查。逐项核对是词语误报，合并提交明确保留以下理由，不删除或改写经验：

- ExecutionService.executionGrid 读取确切 test_revision，并未把 folder_id 放到 test_revision 或 execution_manifest；组织归属仍查询 folder_placement。
- 新组件 fixture 与 rerun 的 attempt/rowVersion 词各触发一次历史刷新经验。HistoryPanel 仍包括 run/attempt 身份、attempt 版本和每步骤 rowVersion，未退回只用 attempt.rowVersion；既有步骤写后刷新回归通过。
- linux_test_inside 的固定方法集合触发真实 clean/EICAR 经验词语匹配。实际门禁要求确切真实扫描方法、51 专项及 187 全量逐名身份与零 F/E/S；最终真实四阶段和附件浏览器均通过，无 mock、skip 或降低计数。

不依赖文本扫描证明源码一致性：总管使用 Git 树比较，集成的运行代码/设施与已审来源一致；文档归档与验收说明另做增量核对。

## 后续交接

原 worker 交接按项目规则原文归档为 `execution-grid-worker-handoff.md`，功能 handoff 删除；任务清单 C1/C2 和路线图 1b/1d 更新。仅批准本次网格/运行器切片，不把阶段 1、完整目录浏览器或 A03 十四用例全集标成完成。独立故障探针本轮未执行；D 正式迁移预留 V25、D 故障探针 V26，实际文件数需合流后核对。

D `ctx_9ed975ccb1ec` 的离线检查点 `84eb68b8cee216400c107cb7d90c08029c0b281e` 尚未验收。同步此已验收 C 基线后，保留全部历史快照，逐名复核 unit/full 与 51+15 专项库存，独立适配设施；预计 95/202 不是通过事实。升级从非空 V24 到 V25，local browser 增为含复核的四流程。总管审查新源后串行验证，不直接运行旧 89/175 门禁或抢号。复核、签署、报告及 A03 最终验收继续按既有计划推进。
