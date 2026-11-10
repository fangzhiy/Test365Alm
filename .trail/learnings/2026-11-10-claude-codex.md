---
title: "以 Claude 为主写代码，额度不够时 Codex 在同一分支接力，靠仓库文件共享记忆"
author: "周"
date: 2026-11-10
branch: dev
cost: { minutes: 2, tokens: 162679 }
id: l-ee4d920c
scope: repo
status: active
src: agent
touches:
  - AGENTS.md
  - CLAUDE.md
---

## Decided
- 2026-11-10 用户明确：主要不是让 Claude 派一堆 worker，而是 Claude 负责大纲和主要代码；Claude 额度不够时 Codex 在同一个文件夹、同一个分支上接着写。所以接力不新开分支，平时不需要合并，只有功能完成时才合并回 dev。
- dev 是本地集成分支，Orca 新建 worktree 的基准分支也设成了 dev。

## Tried and ruled out
- 以 Orca 编排派 Codex worker 为主要工作方式：用户不想要，只作为偶尔并行的补充。
- 依赖 Claude 的私有记忆交接：Claude 的自动记忆按文件夹路径存放，换个目录（Test365Alm 主文件夹、Orca worktree）就读不到，Codex 永远读不到。

## Watch out
- Claude Code 只自动读 CLAUDE.md，不读 AGENTS.md；没有 CLAUDE.md 时规则只对 Codex 生效。CLAUDE.md 用 @AGENTS.md 引用，规则只维护一份。
- 这台机器上 5173 被用户其他项目容器占用、8080 被 sub2api 占用；演示环境默认用前端 15173、后端 18083，绝不停止或修改占用者。Docker 里还有用户其他项目的容器，不能碰。

In the code:
- 交接文件：docs/handoff/<分支名>.md；接手前读它和 git log --oneline -15
- 让 Codex 接手：orca agent launch --agent codex --worktree path:D:/code/ProOrca/Wetest365ALM/Test365Alm
