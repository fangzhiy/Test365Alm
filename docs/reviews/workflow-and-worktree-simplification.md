# 单Codex流程与worktree精简

2026-11-11，按用户明确决定执行。只保留主目录dev和从dev@d6a9479创建的codex-main，由当前Codex直接开发，不再派worker或独立审查者。每功能执行后端单元、Linux完整集成、前端lint/test/build及trail check，通过后合入dev并将验收成果推进main；每功能仅保留一份简短记录。F报告/G A03仍暂停。

## 本次操作

- 12个原worktree的local-evidence（含3个无证据目录）已归档到外层 `evidence-archive/<worktree名>/`，共61,536文件、2,888,627,937字节，源/目标相对路径、文件数和大小一致。W1停止后再次同步核对；外层.gitignore已忽略归档，证据索引已更新。
- 删除audit-history、coordinator-handoff、feat-business-fields、feat-execution-grid、feat-review、feat-signature、integrate-audit、integrate-folders、r2-merge-w2、w1-demo-env、w2-folder-tree共11个旧worktree。使用Orca rm --force，不删除分支；删除前仅有两份原自动配置修改，未丢弃其他未提交改动。
- W1使用原demo_down.py正常停止：4个demo容器已停，4个数据卷保留，test365alm-r02-postgres-1运行状态/启动时间未变。私有凭据及停止后状态已迁到codex-main，并通过原工具核验归属、重建workspace状态；未重启演示。W2残留Vite占用25417，确认归属及无受保护端口后停止，随后Orca删除成功。
- 更新AGENTS、工作台、恢复指南和交付计划；旧状态快照明确标为历史。权限/RLS/不可变历史/审计约束、现有自动化测试、禁止retry和秘密保护均保留。

## audit-history的e8dfdf8

审查 `e8dfdf86da92cc037757f267d7f487c2ed782ced` 后决定**放弃重复合并**，不丢弃原提交：feat/audit-history分支和该HEAD保留。

该提交修复实际workers/retries报告和早期配置/daemon拒绝后的秘密清理。当前dev的localReporter.ts与它blob完全相同；报告器两项回归已存在并扩展，runner已经包含preexisting_secrets/creation_started/拒绝前不碰资源/仅删除新秘密等同一逻辑，main级早期拒绝回归及原follow-up文档也已保留。其他文件差异来自后来资产和五UI扩展，不应重新应用旧三UI版本覆盖当前设施，因此没有新增产品或测试工具代码。

## 验证与限制

本次仅调整规则、文档和本机工作区，没有改产品、测试或runner代码，因此未重跑产品后端/前端全套。提交前检查文档链接、归档计数与路径、两个剩余worktree、11个保留分支、演示停止及数据卷保留，并运行trail check和git diff --check。

链接、归档和diff检查通过。Trail check返回2项词语冲突、无learning重叠：AGENTS中的工作区路径被误读成恢复旧目录，工作台中的GitHub分支说明被误读成只推dev；实际仍沿用外层Test365Alm仓库、main默认/dev集成，且新learning按用户决定替代每功能worktree接力规则。这两项不构成约束冲突，原exit1保留并在提交说明解释，没有编辑旧learning或弱化测试。

历史实测仍按原源/原run引用，不改写为本次测试。历史local-evidence已从旧worktree迁走，新的clone不会自动获得；本机归档可能含秘密，不能提交。旧总管流程learning被新learning替代，原文件保留；旧人工逐名门槛和逐轮窗口/独立报告不再作为新开发流程。
