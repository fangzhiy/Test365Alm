# 开发工作台与共享交接

更新：2026-11-11。这里是当前工作入口；用户最新决定优先于旧会话、旧交接和旧learning中的总管/worker流程。

## 当前工作方式

- 当前Codex直接开发，不派worker，不单独派审查者。只保留主代码目录和从dev新建的codex-main，不为每个功能创建新worktree。
- 一个功能完成：后端单元测试 → Linux容器完整集成测试 → 前端lint/test/build → trail check；全部通过后合入dev，验收成果推进main并推送。
- 不人工逐条核对测试名，不再逐轮申请独占窗口或编写资源清理的独立核验报告。既有自动化门禁继续保留，不删测试、不加retry。
- 每个功能仅在docs/reviews留一份简短记录（做了什么、测试结果、已知限制）；值得留下的原因和取舍用trail note记录。
- 保留权限/RLS/强版本/持久幂等与重放重新授权、不可变历史、审计/Outbox同事务；不提交秘密。不碰其他项目容器、test365alm-r02-postgres-1及5173/8080，不改Defender。
- **用户已恢复 F-REPORT-01；三类只读报告已完成本地技术验证，见 [集中交付记录](reviews/F-REPORT-01.md)。只集成 dev，main 待独立审核。G/A03 仍暂停。**

## 仅保留的工作区

| 工作区 | 分支 | 用途 |
|---|---|---|
| D:/code/ProOrca/Wetest365ALM/Test365Alm | dev | 主目录，仅集成 |
| C:/Users/22538/orca/workspaces/Test365Alm/codex-main | codex-main | 唯一Codex开发工作区，从dev@d6a9479创建 |

GitHub：[fangzhiy/Test365Alm](https://github.com/fangzhiy/Test365Alm)。main为默认主分支，dev为集成分支；本次整理前main/dev为d6a9479。旧分支全部保留，旧chore/r01-status-001不再是默认分支。新功能在codex-main开发，不再新建feat分支或Orca任务。

## F-REPORT-01 当前轮次

当前为 F-REPORT-01-FIX02：保留已审核 FIX01 语义，只修正 code-only 安全错误解析/分类及报告宿主清理链，补两个宿主与真实第二标签退出后的 401 回归。当前验证和最终结果见 [FIX02 集中记录](reviews/F-REPORT-01-FIX02.md)。仍只同步 dev、更新 PR #12，main 待独立审核；G/A03 暂停。FIX02 未改打印，沿用已审核 PR CI 42986595142 样张（运行12/汇总11/偏差8/普通3页）；FIX01 本地汇总12页是另一份来源。

F-REPORT-01-FIX01 修复及技术验证完成，见 [FIX01 集中记录](reviews/F-REPORT-01-FIX01.md)：重开周期、关联关闭签署、失权宿主清理、DTO 和默认打印完善；只同步 dev、更新原 PR #12，main 仍待独立审核。新合成 PDF 技术检查完成，业务格式认可待确认，G/A03 未启动。

审核基线 d7dca900f868aa20a1709272f68f1260a7cbebf6，保留 AUTH-FIX-01 和 push 仅 dev/main 的 CI 配置。执行记录绑定确切 Attempt 与原修订；汇总采用实例最新启动 Attempt 后筛选；偏差范围由真实来源/复测关系决定。报告不持久化、不补签、不写业务状态。技术验证与业务格式认可分开，合成样张不是 A03 业务验收。后端 1bbec573 的 Linux 107/225 全绿；前端/真实浏览器 9be934e 为 458 测试及六条真实流程全绿，三类 PDF 12/6/4 页逐页检查；精确命令、范围、CI 和限制集中在本轮 review。

## 当前已交付与待办（以下为原轮次历史）

A审计、B业务字段、C执行网格、D指定身份复核、E模拟签署/锁定均已验收并集成。现有清单见[tasks/oq-delivery-todo.md](../tasks/oq-delivery-todo.md)，阶段状态见[路线图第7节](alm-replacement-roadmap.md#7-阶段状态)。

最新真实后端证据源c549b25/run8eef9cc7836a4a6698d84fae4587db2f：102单元、82专项、218完整集成零F/E/S，真实clean/EICAR通过。最新实际UI源3af82c4/run78aa3de77a98483392c8f61b6a21043c：五条真实Keycloak业务UI通过，1worker/0retry/0skip，真实资产与双SCRAM通过。后续文档提交不冒称重新执行了这些测试。

正式迁移实际V1..V26，旧历史迁移不改；V27独立故障探针、16UI全集、F报告和真实授权A03十四用例仍未完成。完整证据边界和历史失败见[证据索引](handover/evidence-index.md)及既有reviews。

## 本次精简结果

- 11个旧worktree已用Orca移除，分支和提交保留；只丢弃原有两份自动配置修改，没有丢弃其他未提交改动。
- 所有旧local-evidence已拷贝到 `D:/code/ProOrca/Wetest365ALM/evidence-archive/<worktree名>/`，外层.gitignore已忽略。归档文件数、相对路径和文件大小核对一致，统计见[证据索引](handover/evidence-index.md)。归档可能包含私密运行材料，不能提交到GitHub。
- audit-history未合并提交e8dfdf86da92cc037757f267d7f487c2ed782ced已审查：报告器和早期拒绝清理修复已在dev，现有测试继续覆盖并扩展；放弃重复合并，原feat/audit-history分支及提交保留。理由见[本次简短记录](reviews/workflow-and-worktree-simplification.md)。
- w1-demo-env已用原demo_down.py正常停止，4个demo容器已停、4个数据卷保留；test365alm-r02-postgres-1状态未变。演示配置/凭据已私下迁至codex-main，并用原工具重建该工作区状态，未重启服务。

## 验证与演示入口

Java21：`D:/code/ProOrca/Wetest365ALM/.tools/jdk-21`；系统默认Java17不能替代。后端单元在apps/server运行 `mvn -B -ntp test`。Linux完整集成可使用根目录 `python tools/linux_test_runner.py --source <已提交SHA>`；它保留原自动化扫描/完整集成门禁，不需另行申请资源窗口。前端在apps/web运行 `npm run lint`、`npm run test:run`、`npm run build`，最后运行trail check。

演示已迁移到codex-main。以后从该目录运行 `python tools/demo_up.py`，正常停止用 `python tools/demo_down.py`；不要加delete-data。它会使用私有.env.demo和local-evidence/demo，接回原卷。若改由主目录启动，先迁移相同配置并通过demo_up的归属检查、重建workspace状态；不要两处同时启动，也不要重新生成密码覆盖已有卷。启动新代码可能升级演示数据库，需按当时任务范围判断。

## 阅读与历史

- [恢复指南](handover/resume-guide.md)：现在的单Codex流程及F→G范围。
- [证据索引](handover/evidence-index.md)：新的归档路径、历史测试源及限制。
- [2026-11-11交接快照](handover/2026-11-11-status.md)：精简前的历史快照；旧路径和派工流程已被本页取代。
- 原完整工作台保留于Git历史d6a9479，不再作为现行派工/审查指令。旧Orca Run run_ac1961a61108及F/G任务仅为历史记录，恢复时由当前Codex直接开发，不再启动worker。
