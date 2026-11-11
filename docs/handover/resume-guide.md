# 暂停后的接手与恢复指南

先读 [当前工作台](../coordinator-handoff.md)、[交接快照](2026-11-11-status.md) 和 [证据索引](evidence-index.md)。本指南记录恢复顺序，不是恢复授权；只有用户明确恢复后才启动F/G或真实资源。

## 可以直接进行的接手核对

1. 阅读外层和代码仓库AGENTS.md，确认当前总管身份；原Claude总管已转交接，不并行调度或移动dev。
2. 读取实时Git状态、远端默认分支、main/dev关系。交接前发布基线是cc54867，交接文档提交在其之后；不要从旧功能分支或旧默认分支继续集成。
3. 查看GitHub对应提交的Actions运行，将最新远端CI结果与历史本机验证分开记录。
4. 读取相关Trail节点和learning，再定位代码；不改旧learning。正式改动完成后执行trail check，真实冲突修复或在提交说明逐项解释。

主代码目录内可使用以下只读命令；获取远端状态会更新远端跟踪引用：

```powershell
git status --short --branch
git log -5 --oneline
git fetch origin
git ls-remote --symref origin HEAD refs/heads/main refs/heads/dev
git log --oneline origin/main..dev
```

主目录固定dev，不在那里开展功能实现。总管现有集成worktree为integration/audit；功能交给Orca管理的独立worktree，不自行创建重复worker或Git worktree。

## 现有Orca任务

Run为 `run_ac1961a61108`，repo ID为 `7d777de0-65fb-4c9d-bb61-96d9ab74c24f`。本机CLI路径为 `C:/Users/22538/AppData/Local/Programs/orca/resources/bin/orca.exe`；新会话先按orchestration技能解析实际CLI并读取版本匹配指南。

| 任务 | ID | 交接时系统状态 | 用户执行边界 |
|---|---|---|---|
| B业务字段 | task_6b387a82053b | completed | 已验收 |
| C执行网格 | task_f8104afdeffd | completed | 已验收 |
| D复核 | task_7c52218a8a7e | completed | 已验收 |
| E签署 | task_c0b740c62973 | completed | 已验收 |
| F报告 | task_cabd1dbc25ec | ready | 暂停，不因ready自动启动 |
| G/A03 | task_6d9e7ae23e43 | pending，依赖F | 暂停 |
| 暂停通知 | task_117b21ef9b8e | completed | E已确认停止后续工作 |

E原dispatch为ctx_459c810d228c；暂停确认dispatch为ctx_4892bb53646b。不要因旧消息含“等待放行”或“可worker_done”而重启已结束窗口。Orca消息自动唤醒，不轮询邮箱；仅文档任务不派生功能任务。

## 用户明确恢复后：先F，再G

F先读取现有任务原规格、路线图第7节、交付计划和当前契约，使用最新已集成基线。保留现有任务ID和依赖，不另建重复任务。

F范围是授权数据的运行/测试集/偏差打印报告：固定步骤、实际结果、结论、真实执行时间、附件证据、偏差、复核和三种含义签署。历史未知时间继续明确未知，不能用最近保存时间补造。报告绑定确切历史快照，不能把最新状态替换历史；技术布局通过与用户业务格式认可分别记录。

先提交契约与离线薄片、逐名库存和回归；真实DB/HTTP/资产/浏览器验证须先交确切SHA供总管审查并安排独占串行窗口。F不扩为真实签名厂商认证、数据迁移或G/A03。中文PDF/打印结果需要实际渲染和逐页视觉检查。

G仅在F验收并合入后进行。先区分真实授权A03原文与合成演示数据，不能把演示种子宣称为真实业务全集验收；沿目录→复核→执行→偏差→复测→模拟签署→报告→历史验证十四用例，附最终组合门禁、独立故障探针和精确清理证据。

## 后续变更必须保留的边界

- 当前库存102unit/218IT/82专项、原五条真实UI身份及六Job全部保留；新增测试先逐名扩展，缺失/替换/重复/失败/错误/跳过都应失败。不能任意改selector或降低count刷绿。
- 当前正式V26。旧V1..V26不改；新增迁移/故障探针按实际文件分配版本，不预建runtime角色掩盖schema-only升级问题。
- 当前权限、RLS、强版本、持久幂等及重放重新授权、不可变历史、审计与Outbox同事务不退化。终态签署独立记录，不为签署自增终态业务版本。
- 未知/in-flight命令保留原body/ETag/key，列表/目录迟到回调先检查scope锁；真正项目/身份/权限/reset和拒绝访问必须清空并隔离迟到响应。
- Windows实际使用Java21。真实扫描放在已验证Linux路径；不改Defender、不mock扫描，不触碰W1、日常env或未知Docker资源。避开5173/8080/15173/18083。

## 每个新真实窗口的结束条件

开始前重新核验context/Engine、保护基线、唯一run/source/project标签、完整资源ID/Created/Image和确切卷名。只允许确切源archive、随机回环端口与凭据。密码不进入产品API或脱敏证据。

同一窗口串行执行固定阶段，首失败停止后续阶段，不自动重跑；保留失败报告和准确NOT_RUN。修改后以新提交申请新窗口，不拿前轮部分绿色替代新一轮完整业务流。

结束后保留runner原结果，再独立核验完整ID和精确卷名不存在、run/project所有资源标签空、生成秘密删除、捕获PID+Created消失及所属Java/Node0，并核对保护基线。只处理有归属证明的本任务资源，禁止全局prune。

## 验收与发布

总管复核确切源、证据适用范围和清理后，原worker交接逐字归档到docs/reviews，写总管review，更新清单、路线图第7节及工作台，再本地合入dev。验证后的同一交付结果推进main并推送main/dev；旧分支保留，不强推。推送GitHub不等于部署授权。
