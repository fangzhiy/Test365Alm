# 审计历史及扫描器修复：总管复核检查点

2026-11-11，当前结论：未发现已审范围内的确定阻塞问题；尚不批准审计业务合并，等待完整集成和真实浏览器验收。

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

## 待验收

1. Linux组合后端门槛已通过：源 `34c19f615c8f80a4e28c64e233be613a3360b7d5`，run `f9aa5b46eb0647908e62affaabeb5eb3`；总管已独立读取该工作区 `local-evidence/linux-tests/<run>/` 的scan/unit/integration/run脱敏JSON，真实扫描预检1、单元85、唯一完整集成167均0F/E/S、退出0、计数验证true、cleanup true。镜像内容ID `sha256:9e97c60376cf6eefaee97ff7e0906abcc07207de5172e1f1c0d9cbe74921adba`。不将后加浏览器设施算入这个被测源。
2. 目录树已进 `dev@da47293`；`integration/audit` 保留目录与历史入口。总管实跑前端 246、Java 单元 85、Python最终155通过，lint/build通过（9个既有lint警告）。对照测试差异确认比审计单线新增9个FolderDatabaseIT和1个真实OIDC方法，Linux精确门槛为85单元／167集成。
3. 三条真实 Keycloak 浏览器流程仍 NOT_RUN，合成夹具不替代实际验收；不伪造 CI-owned 变量。
4. 性能建议（非本阶段阻塞）：用例历史按修订缓存，相邻修订仍逐版读取，最坏请求数随页大小增长；演示数据少且页大小有上限，数据量增大时再测量并批量读取，不提前扩大本轮改动。

后续以精确提交、实际报告和这份检查点补充结论；不把“未发现问题”改写为完整验收。

## 经验导入冲突

目录树记录与审计分支两份记录共用 `l-0b383489`。目录记录原文保留；两份审计原始字节分别归档到本目录 `audit-history-original-learning.md`、`audit-history-storage-original-learning.md`，审计决策由新建的独立 ID 经验继续提供。未手改旧经验内容或 ID；活动经验 ID 已逐项验证唯一。

## 浏览器资源守卫前置修复

总管复核 `c8724e4`：`verify_manifest` 的标签判断错误位于内层循环之外，只有同类最后一个资源被判断；现在对每个container/network/volume都检查项目及run标签。回归以首个foreign/最后owned容器证明旧实现错误接受。保留context/Engine和所有权失败拒绝路径，未放宽CI识别。总管组合该提交后实际跑Python156项通过。浏览器local-owned设施仍在worker开发中，不提前批准其实现或宣称真实UI通过。
