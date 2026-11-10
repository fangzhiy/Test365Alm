# 审计历史及扫描器修复：总管复核检查点

2026-11-11，当前结论：后端组合完整集成、真实Keycloak三入口及资源清理门槛均已满足，总管批准审计历史本地dev集成。本文保留历史检查点及被测源区别，不宣称其他阶段完成。

## 已审范围

- 审计后端 `d78d0b9`：HistoryService、HistoryDiff、HistoryController；对照 HistoryDiffTest 六项和 HistoryDatabaseIT 五项的实际断言。
- 审计前端 `4ff8aae`／`a797f3f` 的 HistoryPanel 请求生命周期及三入口接入设计；复核 `814d5db` 中缺陷复测／同版本刷新触发与回归。
- 扫描器测试设施 `4764d63`：pauseScanner／resumeScanner、原进程和发布端点检查、七项隔离守卫回归。
- Linux runner `ef27b7a`：已复核固定源码归档、最小子进程环境、镜像内容身份、逐次 daemon/context 核验、精确 ID 清理及白名单报告导出。Docker socket 赋予完整 daemon 权限，标签只作操作守卫；离线测试不等于业务集成验收。

## 结论与证据

- 对象类型白名单后选择固定表名；查询值参数化，先项目权限与事务 RLS 上下文，再确认对象 tenant/project 归属。用例／缺陷来源是相邻不可变修订，运行来源是同项目 execution_event，未从哈希编造字段值。
- 历史使用倒序 `(at,id)` 有限 keyset 分页，校验不透明游标；运行步骤 join 绑定同项目 attempt 与 stepKey。数据库测试断言真实修订差异、运行生命周期、跨项目／错类型／撤权拒绝和不漏不重分页。
- HistoryPanel 对对象变化建立独立请求会话，取消旧请求；403／404 局部显示，不关闭父详情；分页失败保留已有页。缺陷复测成功不一定改变 rowVersion，增加独立刷新计数与该行为匹配，回归还确认复测命令只发送一次。
- scanner 允许的例外仅为暂停期间 live Ports 缺失；容器标签、ID、启动时间、配置绑定继续检查，恢复后发布端口严格匹配。post-pause 检查异常时在调用者 finally 进入前恢复原归属可证进程，再抛原异常；未知或被替换资源不自动恢复。
- worker 已报告 79 单元、两个真实暂停恢复方法、前端 235 项和 lint/build 通过；其完整 Windows 集成仍为 157 项 1 failure／5 errors／0 skipped，六项均发生于 EICAR 上传，并有对应 Defender 检测／隔离事件。总管已读功能交接及相关代码／回归，未把未直接运行的测试称为本轮自己重跑。

## 验收结论

1. Linux组合后端门槛已通过：源 `34c19f615c8f80a4e28c64e233be613a3360b7d5`，run `f9aa5b46eb0647908e62affaabeb5eb3`；总管已独立读取该工作区 `local-evidence/linux-tests/<run>/` 的scan/unit/integration/run脱敏JSON，真实扫描预检1、单元85、唯一完整集成167均0F/E/S、退出0、计数验证true、cleanup true。镜像内容ID `sha256:9e97c60376cf6eefaee97ff7e0906abcc07207de5172e1f1c0d9cbe74921adba`。不将后加浏览器设施算入这个被测源。
2. 目录树已进 `dev@da47293`；`integration/audit` 保留目录与历史入口。总管实跑前端 246、Java 单元 85、Python最终155通过，lint/build通过（9个既有lint警告）。对照测试差异确认比审计单线新增9个FolderDatabaseIT和1个真实OIDC方法，Linux精确门槛为85单元／167集成。
3. 真实Keycloak三流程已通过：确切源 `4a135897a964c92bdb4e2fe6e3dba29e738c0ff3`，run `06caafafb42e4762b6f4b9f222265ab7`。总管独立读取 `local-evidence/local-browser/<run>/{results,run,manifest}.json`：用例/执行/缺陷三个确切方法均passed/expected passed/retry0，单worker、零重试、无skip，exit0、cleanup true、processes_stopped true，source/project/run/context/Engine相符。总管分支与4a13589的apps/tools/contracts/.github完全相同；34c19f6→4a13589无后端源码或测试差异，所以完整167后端报告适用同一后端实现。浏览器环境assets disabled，附件仍由独立Linux完整真实扫描集成验收，不把三UI流程扩称附件浏览器验收。
4. 性能建议（非本阶段阻塞）：用例历史按修订缓存，相邻修订仍逐版读取，最坏请求数随页大小增长；演示数据少且页大小有上限，数据量增大时再测量并批量读取，不提前扩大本轮改动。

本次仅验收审计历史及其已审测试设施。复核/签署/报告/A03完整业务闭环仍按独立清单推进。原功能交接归档为 `audit-history-worker-handoff.md`，删除功能handoff入口；后续非阻塞设施改进独立复核，不混入已测源。

## 经验导入冲突

目录树记录与审计分支两份记录共用 `l-0b383489`。目录记录原文保留；两份审计原始字节分别归档到本目录 `audit-history-original-learning.md`、`audit-history-storage-original-learning.md`，审计决策由新建的独立 ID 经验继续提供。未手改旧经验内容或 ID；活动经验 ID 已逐项验证唯一。

## 浏览器资源守卫前置修复

总管复核 `c8724e4`：`verify_manifest` 的标签判断错误位于内层循环之外，只有同类最后一个资源被判断；现在对每个container/network/volume都检查项目及run标签。回归以首个foreign/最后owned容器证明旧实现错误接受。保留context/Engine和所有权失败拒绝路径，未放宽CI识别。总管组合该提交后实际跑Python156项通过。该检查点当时浏览器local-owned设施尚未完成；后续审查和通过事实见下文及验收结论。

## 明确本地模式设施审查（4a13589）

已读 `local_browser_runner.py` 的完整资源创建/核验/异常清理路径、`localOwnership.ts`、`ownedR03.ts`、本地配置和脱敏reporter及新增负例。首次创建前拒绝预存项目；归档固定受控SHA，临时随机凭据和端口，读取隔离环境；Keycloak realm不绑Windows，复制后UID1000/0400。容器操作与清理核验project/run/source、context/Engine和ID/Created/Image。仅三个业务流程可使用local-owned门禁，身份故障注入仍需原CI识别；本地PG播种使用核验后的精确ID。

未发现阻塞当前三个流程的设施问题。worker报告Python161、前端248、lint/build、E2E配置独立tsc和trail check通过；这些为worker实际执行的检查，总管已审相关实现/断言，没有重复宣称自己重跑。新增负例现覆盖container/network/volume首foreign末owned、本地身份变化、既有凭据保护与三条确切pass且零retry/skip报告验证。

两项非阻塞建议已发worker：reporter应从FullConfig读取实际workers/retries，避免将来参数变化而报告仍硬编码1/0；fresh_config和首次daemon检查处于主try之前，早期失败也宜统一清理已生成秘密。当前命令与配置均固定单worker/零retry，确切4a13589真实运行已完成；两项改进独立提交复核，不混淆被测源，不要求对未改业务重复验收。
