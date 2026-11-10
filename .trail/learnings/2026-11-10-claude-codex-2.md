---
title: "项目总管已由 Claude 改为 Codex，旧角色说明不再适用"
author: "周"
date: 2026-11-10
branch: docs/coordinator-handoff
id: l-dff86dcf
scope: repo
status: active
src: agent
touches:
  - AGENTS.md
---

## Decided
2026-11-11 用户明确“现在你是总管，你们两个总管交接好文档”。外层 Codex 接任项目总管，原 Claude 只负责交接，不再并行派活或移动 dev。此前“Claude 始终是总管”的偏好已被本次指令取代。

## Tried and ruled out
只靠旧会话或私有记忆决定当前负责人：恢复额度后可能出现两个总管。必须以版本化的总管工作台作为入口。不能把原总管服务503或消息发送回执当成双方已核对完成。

## Watch out
这条更新只改变当前项目负责人，不替代已有的功能worktree接力规则，也不扩大附件失败的局部接受范围。原W1/W2已接受的基线和审计要求完整集成全绿分别记录，失败不能写全绿。

In the code:
- docs/coordinator-handoff.md 长期记录总管身份、分支事实、证据、验收差异和阻塞。
- AGENTS.md 顶部更新角色优先级，保留原有工程约束。
