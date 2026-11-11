# 恢复开发指南（单Codex）

现行规则以[AGENTS.md](../../AGENTS.md)和[开发工作台](../coordinator-handoff.md)为准。本指南取代旧总管/worker/独立审查及每轮资源窗口流程。用户已恢复 F 报告，当前执行 F-REPORT-01-FIX02，只同步 dev、更新 PR #12，main 待独立审核；G/A03 仍暂停。

## 开工

读取规则、工作台、相关契约和实时Git状态，再用Trail定位代码。主目录固定dev；唯一开发worktree是 `C:/Users/22538/orca/workspaces/Test365Alm/codex-main`，分支codex-main。由当前Codex直接完成实现和验证，不创建worker、审查者或新的功能worktree。

开工可核对 `git status --short --branch`、`git log -5 --oneline` 和远端main/dev。确认开发区干净后，可在codex-main将dev以 `git merge --ff-only dev` 同步过来。不得覆盖用户改动。

## 一个功能的交付

1. 在codex-main完成一个功能，保留现有测试和数据约束。
2. Java21后端单元测试；Linux容器内完整集成测试；前端lint/test/build；trail check。
3. 全通过后，在docs/reviews只写一份简短记录：做了什么、实际测试结果、已知限制。重要原因和取舍用trail note。
4. 合入dev，验收成果推进main并推送。main/dev承担原有角色，不强推，不删除历史分支。

不人工逐条核对方法名，不申请每轮独占窗口，不额外制作独立资源清理核验报告。既有自动化门禁继续执行，不删测试、不加retry。只清理自己的临时测试资源，不碰其他项目容器、test365alm-r02-postgres-1、5173/8080，不改Defender；私密文件不入库。

## 用户明确恢复后，先F再G

F做运行/测试集/偏差报告：授权数据、冻结步骤、实际结果、结论、真实执行时间、附件、偏差、复核和三种含义签署。历史未知时间仍明确未知；中文打印/PDF需渲染核对。技术通过和业务方格式认可分开记录，不替用户宣称认可。

G在F验收合入后开展真实授权A03十四用例闭环：目录→复核→执行→偏差→复测→模拟签署→报告→历史。区分真实授权原文与合成演示数据；不得把演示种子当业务全集已验收。

旧Orca F任务task_cabd1dbc25ec为ready、G任务task_6d9e7ae23e43为pending，仅作为历史进度参考，不因此自动启动worker。

## 迁移、历史和证据

当前正式V1..V26；旧历史迁移不改。如新功能确需schema变更，只追加新版本并相应顺延故障探针；报告优先授权只读查询。权限/RLS/强版本/持久幂等/重放重新授权、不可变历史、审计与Outbox同事务仍然必须保留。

旧local-evidence集中在 `D:/code/ProOrca/Wetest365ALM/evidence-archive/<原worktree名>/`，见[证据索引](evidence-index.md)。不能因原worktree删除而把历史失败改为通过，也不能把后续文档提交写成产品测试源。

## 演示环境

四个test365alm-demo容器已正常停止，四个数据卷保留。codex-main已保留原凭据/realm/播种记录并重建workspace状态，运行 `python tools/demo_up.py` 可由该目录接回演示；本次没有启动它。停止用 `python tools/demo_down.py`，不要delete-data。

如需从主目录启动，先按同样方法迁移私有配置并用现有归属守卫重建workspace状态；不要两处同时启动，不生成新密码去接旧卷。test365alm-r02-postgres-1和5173/8080始终受保护。
