---
title: "沿用 Test365Alm 代码，从 GitHub 克隆到 Wetest365ALM 下开发"
author: "周"
date: 2026-11-10
branch: chore/trail-setup
cost: { minutes: 3, tokens: 32189 }
id: l-0ba5b7b0
scope: repo
status: active
src: agent
touches:
  - AGENTS.md
  - README.md
---

## Decided
- 2026-11-10 用户选择：Wetest365ALM 的代码沿用已有的 Test365Alm，从 GitHub 克隆到 Wetest365ALM 目录下的 Test365Alm 子目录开发，保留完整 git 历史和与 GitHub 的关联。
- 外层 Wetest365ALM 只放需求文档（zl/）和自己的 trail 经验，用 .gitignore 忽略 Test365Alm/，两个仓库互不嵌套。
- Orca 里添加的项目应是 Test365Alm 这一层，worker 的 worktree 按这个仓库开。

## Tried and ruled out
- 直接复制原目录：会丢掉 git 历史和远程关联，还会带上约 388M 的历史产物和 node_modules。
- 在原目录直接工作：经验和 Wetest365ALM 的文档分开，Orca 项目也分散，不选。
- 重新起一个精简项目：没选，先沿用现有代码；原规划 31 个模块偏重，范围收敛仍以签署、审计、三层测试模型为核心。

## Watch out
- 原目录 D:/code/project/test365Alm/jihua/Test365Alm_ProjectPackage/Test365Alm 里有 3 个从未推送的文件（docs/development-status.md 的改动和两份 2026-11-03 评估记录），克隆里没有。
- 这里的 AGENTS.md 要求走任务分支和 PR，不要直接在默认分支上提交。

In the code:
- 远程 https://github.com/fangzhiy/Test365Alm.git，默认分支 chore/r01-status-001（已合并 PR #10 feat/r11-m05-001）
- trail 接入在分支 chore/trail-setup，未推送
