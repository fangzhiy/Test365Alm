# R09-CORE-BATCH02 执行记录

日期：2026-10-16。输入全文：[原文](../inputs/R09-CORE-BATCH02.md)。实际仓库 `fangzhiy/Test365Alm`，本地位于 `jihua/Test365Alm_ProjectPackage/Test365Alm`。仅本批次记录连续定位/修复，不拆成多个 FIX 轮次；无自动合并、部署、定时任务恢复或默认分支配置变更。

## 起点与阶段 A

开始时工作区干净，分支 `feat/r08-m04-m11-001`，本地与真实远端 head 都是 `740ac21980b41a8ca49be616e7df4a4b8904e881`。实际 fetch 成功，PR #7 Open、未合并、Mergeable=true，base `chore/r01-status-001@1e49617f283ffa54673d590a1aba3e72fcfbe14e`；PR #1—#6 已合并。重新读取历史交付 Push38213436949/PR38213441555，均 completed/success，不沿用旧“未合并”快照。

读取 AGENTS、当前状态、R08 全文/记录、M04/M10/M11、领域模型、需求/用例/执行/追踪契约与实际源码。代码审查与安全技能用于范围内自审，另有只读独立审查，不把内部自审称为外部审核。

发现并修复 NUMBER 零边界：precision==scale 时零不需要整数位，旧实现误拒绝 `0/-0`。先执行 `mvn -B -ntp test '-Dtest=FieldRulesTest'`，退出1、4测试中1失败，确切 `Decimal exceeds precision or scale`；最小修复只影响零的整数位计算，小数位限制不变。完整 `mvn -B -ntp test` 后退出0，42测试无失败/错误/跳过。

补真实数据库 FAIL→PASS→关联失效与需求修订变化覆盖证据；保留原未执行/活动重跑规则，字段修订 audit/Outbox 故障完整回滚；实际 OIDC 旧会话直接访问字段/追踪的停用拒绝（403/FORBIDDEN），未先调用 /me。R08 精确名称门禁要求新增覆盖方法。

Attempt A1：新增测试一处误用了 rerun 的返回类型，testCompile退出1，修正为现有 AttemptView；没有改产品接口。Attempt A2：本机真实集成命令 `mvn -B -ntp -Pintegration verify '-Dit.test=CoreMetadataTraceDatabaseIT,OidcCallbackSecurityIT'` 退出1，Failsafe两个类错误，原因Docker Desktop Linux命名管道不存在。`docker version` 确认 daemon 不可达（不是数据库行为通过）；本机集成 BLOCKED，继续用既有隔离 Ubuntu CI。没有删除/跳过测试或连接开发库。

本机前端 `npm run test:run`、`npm run build`、`npm run lint` 均退出0，145测试通过；普通既有React lint warnings保留，不扩成依赖升级。根目录 Python unittest80通过、validate_package退出0；规划状态仍 NOT_EXECUTED/P0 inputs missing。技能引用的本机公共 references 文件缺失，沿用本仓库 AGENTS/验收作为完成门，不据此放宽要求。

阶段 A 当前代码/CI结果在对应运行完成后记录；门未通过不进入 M10 分支。后续最终交付 SHA/CI仅在回复收口，避免自引用文档循环。
