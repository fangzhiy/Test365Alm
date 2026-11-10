# 指定身份用例复核总管验收

2026-11-11。批准 D1/D2 本地 dev 集成。最终 worker 交接为 `a27d3063c7d919700202e4466d9923617ba0e6e9`；后端真实测试源为 `6fa15ce79a91158e5a61dc2258be3366d1998a33`，四条真实浏览器源为 `afa0f4aa9915b4f2a0701ca1c10906befc1b968b`。前置已验收 C 基线 `1b5ee2353f4b36ababb835dce2ababd1b98c0b9c` 是最终交接的祖先。

## 实际证据及适用源码

证据位于 `feat-review` 工作区忽略的 `local-evidence/`，没有把本机秘密或原始日志提交到仓库。总管独立读取原始脱敏阶段报告并调用固定 `valid_report` 核对完整方法集合、重复、聚合和每个方法状态。

| 阶段 | 源与 run | 实际结果 |
|---|---|---|
| 真实 clean/EICAR | 6fa15ce / 5222fc820d024cc49cbc63f285a1ae67 | 95 单元、1 确切真实扫描方法，零 F/E/S、exit0 |
| 单元 | 同上 | 95 个确切方法、31 类，零 F/E/S、exit0 |
| 固定专项 | 同上 | 95 单元、66 集成（C43+B8+D15），零 F/E/S、exit0 |
| 唯一完整集成 | 同上 | 95 单元、202 集成（27 类），零 F/E/S、exit0 |
| 四条真实 Keycloak UI | afa0f4a / r03-local-77c0d60cbc1e448e9c13ea8ee57a1bb8 | 用例、执行、缺陷、指定复核人四个完整身份均 passed/expected passed，workers1/retries0，每条 retry0、无 skip，exit0 |

后端报告目录为 `local-evidence/linux-tests/5222fc820d024cc49cbc63f285a1ae67/`，浏览器为 `local-evidence/local-browser/77c0d60cbc1e448e9c13ea8ee57a1bb8/`。后者明确 real-owned；真实私有 S3/ClamAV clean/EICAR preflight、正式 scanner 两次独立 SCRAM 连接均 PASSED。总管另独立调用四结果严格门禁通过。

6fa15ce 到 afa0f4a 的后端、Linux runner/inside 及逐名库存完全相同，后续仅浏览器准入、起点归属记录、回归与文档；a27d306 相对 afa0f4a 仅 19 行交接增补。没有声称后端在后续源重新执行。worker 离线最终 343 前端/33 文件、201 Python、lint/build 通过（8 个既有 warning），15 条浏览器只是发现；总管独立运行过 75 项父组件/复核相关回归、14 项实际注册回调准入和 18 项资源/资产守卫回归，以及此前 42 项固定门禁回归。

## 总管独立资源核验

后端 runner `cleanup=true`、`cleanup_events=[]`。总管在 default context、Engine `a6f010ac-61a2-4544-b133-c2b0d92d11d3` 自行只读查询：57 个捕获完整容器 ID 均返回对应确切不存在响应，同 run 标签容器/网络/卷为空，Engine 前后一致。

浏览器 runner `cleanup=true`、`processes_stopped=true`。ownership-start 在 create 核验后、start 和业务前捕获 4 个完整 64 位容器 ID及 Created/Image、1 个完整 64 位网络 ID及其实际属性、3 个确切卷名，并记录精确 run/source/project 标签。总管在 desktop-linux、同一 Engine 自行逐项查询确认全部不存在，project/run 标签的容器/网络/卷均为空。三份秘密 `.env.r03`、`local-evidence/r03/realm.json`、`local-evidence/local-browser-compose.json` 均已删除；CIM 同快照 root Java/Node 进程为 0。

总管独立记录在 `integrate-audit/local-evidence/d-coordinator-cleanup.json`。没有删除未知资源、全局清理、触碰 W1/日常环境或改动 Defender。

## 契约审查与已修阻塞

- 复核绑定确切封存修订、指定主体 UUID 和数据库 SHA-256 内容摘要，摘要覆盖身份、修订号、标题、描述、前置条件、完整业务字段和有序步骤；合法大写 UUID 按 UUID 值校验，冻结原文不改。
- 动作与持久幂等重放均先检查当前项目权限、RLS 和冻结 reviewer 的有效 writer 资格。批准/拒绝只能由指定主体执行，admin 不绕过，不增加禁止自复核规则。
- DRAFT/REJECTED → SUBMITTED → APPROVED/REJECTED；拒绝理由必填。内容编辑与复核共享强版本，复核事件、版本、审计、Outbox、冻结响应同事务；运行身份不能修改或删除历史。内容新修订独立从 DRAFT 开始，旧批准和摘要保留。
- 正式 V25；旧 V1..V24 字节不变，历史内容/执行/证据夹具与已验收 C 一致。非空 V24→V25 只执行一份新增迁移，完整旧表原列快照、幂等迁移及正式 runtime 动作实际通过。条件授权不自动创建 runtime 角色，两个既有无角色升级方法仍在完整集合中实际通过。
- 总管首次审查发现同范围列表切换和 Enter 查询会卸载 ReviewSession，丢失未知结果的原命令/版本/幂等键。6fa15ce 在 load/selectTest 清空之前使用同步范围锁，覆盖迟到目录回调并禁用相应入口；真实项目/身份/权限/reset 切换仍清空、abort并隔离迟到响应。41 项父组件回归覆盖未知/in-flight、原意图重试及安全 teardown，总管独立相关 75 项通过。
- 首次四 UI 在 6fa15ce/run1ab1d837 为 3P1skip；D 错用 CI-only 条件，严格结果门禁正确 exit1。afa0f4a 仅将该流程复用现有 isOwnedRun，14 场景调用实际注册回调证明合法 local-owned/CI-owned 到首个 UI 操作，错误归属拒绝；其他 CI-only 故障流程不扩宽。新真实源完整四条重跑全过，没有沿用旧三绿替代。

## 保留的失败与检查边界

原合流脚本截断被离线 Python 发现并恢复，原失败日志保留。原浏览器 3P1skip、总体 passed 但严格 gate exit1、原始网络只保存 12 位 ID 的限制永久保留；不事后补造完整历史身份。worker 的独立清理探针曾因网络/卷 not-found 语法不同拒绝，核对实际精确语法后只读完成，没有资源删除或业务重跑。

总管合流前 `trail check --base 1b5ee23` 检查 48 个文件，返回 5 个词语冲突，逐项核对并在合并说明保留理由：

1. OidcCallbackSecurityIT 新复核 HTTP 方法读取确切 revision，并未把 folder_id 放入冻结修订或 manifest。
2. 同一测试新增复核时间/响应文字，不改变 executedAt，不从 updatedAt 为历史未知执行时间补值。
3. project-access 新真实复核流程使用精确 combobox，未恢复旧 label 歧义；最终四条实际通过。
4. linux_test_inside 的 D 方法闭集保留唯一真实 clean/EICAR、66 专项及 202 全集零 F/E/S 门禁，没有 mock、skip 或降低库存。
5. runner 回归中的固定 selector 保留无角色升级两个方法及完整集合，未预建 runtime 角色掩盖迁移故障。

旧 learning 不删改；历史合流 diff 超过扫描上限的限制沿用 worker 原记录，不以文本工具代替源码树与实际报告比对。本次总管文档归档增量另做 `trail check`。

## 后续

worker 原文完整归档为 `test-case-review-worker-handoff.md`，删除功能 handoff，更新清单 D1/D2 和路线图 2a。仅接受指定身份复核切片；2b/2d 模拟签署与锁定、报告、A03 十四用例尚未完成。独立故意失败 V26 探针及完整 15 条浏览器全集本轮 NOT_RUN，留待后续统一验收。E 从本地已集成 D 基线开始，正式迁移预留 V26、故意失败探针顺延 V27，旧 V1..V25 不改；先离线小切片和固定逐名清单，真实资源仍须串行窗口。
