---
title: "每个功能开一个 worktree，Claude 和 Codex 在里面接力，主文件夹只做合并"
author: "周"
date: 2026-11-10
branch: dev
cost: { minutes: 1, tokens: 6574 }
id: l-8cd67a73
scope: repo
status: active
replaces: l-ee4d920c
src: agent
touches:
  - AGENTS.md
  - CLAUDE.md
---

## Decided
- 用户的工作方式：Claude 负责大纲和主要代码；Claude 额度不够时 Codex 在同一个 worktree、同一个分支上接着写，回来后 Claude 审查并继续。派 Orca worker 只是偶尔的补充。
- 每个功能用 Orca 从 dev 新建一个 worktree（分支 feat/<功能>），Claude 和 Codex 都开在这个 worktree 里。主文件夹固定停在 dev，只用来合并。
- 交接靠仓库文件：docs/handoff/<分支名>.md、提交记录、trail 经验。

## Tried and ruled out
- 让 Claude 和 Codex 直接在主文件夹里写功能：主文件夹还要用来合并其他分支，切分支会和正在写的功能冲突。
- 依赖 Claude 的私有记忆交接：它按文件夹路径存放，换个 worktree 就读不到，Codex 永远读不到。
- 以派 worker 为主要方式：用户明确不想要。

## Watch out
- Claude Code 只自动读 CLAUDE.md，不读 AGENTS.md；CLAUDE.md 用 @AGENTS.md 引用，规则只维护一份。
- 这台机器上 5173 和 8080 被用户其他项目占用，演示环境默认用前端 15173、后端 18083；Docker 里用户其他项目的容器不能碰。

In the code:
- 新建功能 worktree 并启动 Claude：orca worktree create --repo id:7d777de0-65fb-4c9d-bb61-96d9ab74c24f --name <名字> --branch feat/<名字> --agent claude
- 在该 worktree 里启动 Codex 接手：orca agent launch --agent codex --worktree path:C:/Users/22538/orca/workspaces/Test365Alm/<名字>
