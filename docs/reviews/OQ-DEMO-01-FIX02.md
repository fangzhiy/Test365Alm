# OQ-DEMO-01-FIX02

从审核 head `151a8bff92bcc9fba4c6eedb619735248bc8205d` 继续 PR #13，只集成 dev/codex-main；main 保持 `b8975e4604295269c2c0e8efef461614fc4b37da`。保留用户工具配置、历史证据及持久数据。合成演示、模拟签署不是正式 OQ 或电子签名认证；真实 G/A03 暂停，业务报告格式认可待确认。

## 两项修复

1. 同筛选网格刷新保留旧行，超时先使轮次失效，catch 无法清行；通用重试只读测试集却恢复 ready，旧 FINISHED 报告因此复活。新增独立 `gridVerified`：开始完整网格读取即失效，只有当前轮次/范围的成功结果才能恢复。保留正常刷新时的导航身份、交互锁、取消与迟到响应保护，不扩大运行详情读取锁。
2. 原隔离回归只调用纯完成校验，page 请求事件也看不到 APIRequestContext。现在正式场景与回归共用 `resumeAfterDefectPreflight`，先核验全部完成身份，再执行剩余业务动作；回归保存并重新读取完成检查点，人工开启新周期后调用该编排。`captureApiWrites` 在实际 APIRequestContext 方法边界观察 POST/PUT/PATCH/DELETE，原方法和真实响应照常执行；先成功创建隔离测试集证明计数器有效，再清零进入冲突窗口。比较偏差、完整分页修订/复测/审计/Attempt 及各确切对象签署视图。

## RED/GREEN 与被测版本

- 真实 TestExecutionPanel 宿主：A1 FINISHED/导航打开→刷新超时→通用重试仅列表成功，修复前仍出现 A1 报告，1项 RED（`local-evidence/oq-fix02-host-red.log`）。修复后该项 GREEN，并验证迟到响应不能恢复、A2 RUNNING/PAUSED 无报告、FINISHED 请求指向确切 r2/a2。
- 共用 resume 编排专项先 RED（旧代码无该编排边界），后验证冲突不执行后续动作、正常身份才能继续。宿主/导航/恢复专项合计59通过。
- 实现提交 `5b30200f8341883c0e9f45c489c844a68f1e2fdf`；前端 lint 通过（10项既有 warning）、完整测试46文件514/514、build及浏览器代码类型检查通过。构建曾发现测试使用不支持的 Testing Library `exact` 参数，已修正后通过，未改断言含义。
- `python -m unittest discover -s tools/tests -v`：220通过，本轮未修改Python工具。
- `5b30200` 真实隔离浏览器 run `5cafb26df7cb4e0a834393bb9a03f08f`：原六条加十四场景共7/7、单worker/零retry/零skip；正式scanner provision/真实资产预检通过，cleanup与processes_stopped均true。新增故障项目先成功发送一次业务POST验证计数器，再清零；共用编排报冲突，actionsExecuted=0、writes=[]，完整快照一致。`local-evidence/oq-demo-fix02/fix01-regressions.json`、`fix02-recovery-checkpoint.json`保留原始证据。未给持久项目制造故障。
- `5b30200` 持久v1.1 inspect只读；连续两次resume成功。前后业务快照完全相等，SHA256为`e06dfca1ff530f9469f9ce0b056e130be3e83cd2d92deaed26153a6a1def9d5b`：14用例/16Attempt/29模拟签署，CLOSED/REOPENED各一项，原FAIL和独立未签署复测保留。证据`local-evidence/oq-fix02-business-{before,after}.json`及`oq-fix02-resume{1,2}-5b30200.log`。
- `5b30200` Linux run `0c3b08c175eb46a484fb96e26d01e63a`：Java21单元107、专项82、完整集成226，全部零失败/错误/跳过；真实扫描与既有报告门禁通过，exit=0、cleanup=true。原始结构化报告在`local-evidence/linux-tests/0c3b08c175eb46a484fb96e26d01e63a/`。
- 提交后再次以`5b30200`执行前端lint/完整test/build，514/514通过，日志与JSON为`local-evidence/oq-fix02-*-5b30200.*`，不把最初构建类型错误记成通过。后续仅文档/Trail收尾。最终正常dev push和PR CI的运行链接、head/base、实际merge-ref checkout、原始计数及制品结果集中更新在[同一PR #13](https://github.com/fangzhiy/Test365Alm/pull/13)，不将旧轮次结果当成本轮通过。

命令：

```bash
python tools/linux_test_runner.py --source 5b30200f8341883c0e9f45c489c844a68f1e2fdf
python tools/local_browser_runner.py --source 5b30200f8341883c0e9f45c489c844a68f1e2fdf --with-assets --scenario oq-demo-01
# apps/web
npm run lint
npm run test:run -- --reporter=verbose --reporter=json --outputFile.json=../../local-evidence/oq-fix02-vitest.json
npm run build
```

`git diff --check`、Trail build/check 已执行。check 的 l-494264d8 词法冲突把 reset 处的 lock/run/draft 识别为恢复旧的全面运行读取锁；本次只新增网格核验状态，不改变 run-read/preserveDraft 锁或取消轮次机制，相关历史切换回归保留并通过。理由已写入实现提交；不改写旧 learning 或降低检查规则。

打印实现未变；本轮正常流程产生的新样张位于`output/pdf/oq-demo-01-fix02/`，普通运行/原失败/复测/汇总/偏差分别5/6/6/6/6页，来源`5b30200`、上述隔离browser run。已核对合成标识、来源、中文和首末页；首末页渲染与`samples-source.json`在`local-evidence/oq-demo-fix02/`，不冒称本轮重新逐页审核或最终CI样张。旧样张保留原提交来源。演示启动、inspect、显式 resume、停止及逐步查看说明沿用 [FIX01](OQ-DEMO-01-FIX01.md#演示操作)。人工变更冲突应保留现场，不删除检查点或重建数据。本轮最终结果及原始制品链接集中在同一 PR #13，独立审核前不合入 main。
