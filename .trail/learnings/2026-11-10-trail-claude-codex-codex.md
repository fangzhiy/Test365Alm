---
title: "重新接入 trail 时要同时选 Claude 和 Codex，Codex 钩子不用改"
author: "周"
date: 2026-11-10
branch: chore/r01-status-001
cost: { minutes: 2, tokens: 7888 }
id: l-4d09a934
scope: repo
status: active
src: agent
touches:
  - AGENTS.md
  - ".claude/settings.json"
---

## Decided
- 本项目用 trail 让 Claude Code 和 Codex 共享记忆，两边都要接入。
- 用户的 Codex 在 Windows 上已经用 PowerShell 7，hooks.json 里的 '|| exit 0' 能正常执行，不需要按指南去掉（用户 2026-11-10 明确说不用修）。

## Tried and ruled out
- 按指南把 ~/.codex/hooks.json 里的 '|| exit 0' 去掉：用户已升级 PowerShell 7，已还原。

## Watch out
- trail init 的智能体列表里没有 codex 这个 id，Codex 对应的是 agents（写 AGENTS.md 和 ~/.codex 配置）。
- trail init 会删除这次没选的智能体的配置（上次就删掉了 antigravity），所以每次都要把要用的全选上。

In the code:
- 重新接入用：trail init --agents claude agents（可以先加 --dry-run 看会改哪些文件）
- 验证 Codex：codex mcp list 里 trail 显示 enabled
