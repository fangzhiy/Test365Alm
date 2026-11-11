# OQ-DEMO-01-FIX03

2026-11-12，从`a67be50b00308a81857bb3fc237446b79f3dc090`继续PR #13；main保持`b8975e4604295269c2c0e8efef461614fc4b37da`。本轮仅修正已有偏差、缺少完成标记的旧v1.1检查点预检。已审核的gridVerified、宿主回归及导航保护原样保留。用户工具配置、旧项目/卷及历史证据保留；合成数据和模拟签署不代表正式OQ/电子签名认证，真实G/A03暂停，报告业务格式认可待确认。

## 原因、准确RED与修复

`resumeAfterDefectPreflight`原来仅凭marker/global-completed决定是否读取。旧检查点已有真实defectId，但没有defectCompletion、resolutionId及closed-and-reopened-defects阶段，服务端已REOPENED后再RESOLVED时，预检直接进入work。

在未修改的a67be50实现上新增行为反例，实际RED为`proofReads=0、actionsExecuted=1、conflict=null、recorded=0`（1失败/7原测试通过），日志`local-evidence/oq-fix03-exact-legacy-red.log`；不是缺函数或类型错误。

实现提交`e19f8cfc6727fe5fd773a8c92ffc00d45eadfe93`：只有真正没有偏差身份的新场景可跳过；已有defectId必读服务端历史，无标记且多个解决周期先拒绝。按真实completed状态校验，保留合法首周期OPEN/RESOLVED、周期内内容编辑、未完成复测及终态缺标记恢复；全部已有偏差核验通过后才回填可确认的完成标记并进入work。不改后端状态机或安全机制，不把最新周期补认为原周期。

专项GREEN为66项（含原网格超时、宿主及导航测试）。补充测试验证第二个偏差冲突时，第一个偏差即使可补齐终态，也不得提前回填。原完整完成检查点的人工变更、终态后编辑拒绝等回归保留。

## 真实回归和版本

隔离项目使用正常OIDC/CSRF业务API完成OPEN→首RESOLVED→PASS复测→REOPENED，保存旧检查点后再人工RESOLVED。重新读取文件，直接由正式runner共用编排执行：三个标记须确实缺失，不注入marker或completed=true。沿用真实APIRequestContext观察器，先独立成功POST证明观察有效，清零后进入恢复窗口；比较偏差、完整分页修订/复测/审计/Attempt及确切对象签署，同时验证检查点未变。单周期正常恢复和完整完成检查点使用独立验证，不改写A类旧文件。

前端源内容随后冻结为e19f8cf，提交前执行lint（10项既有warning）、完整521/521测试、build及浏览器代码类型检查通过；工具220通过。日志为`local-evidence/oq-fix03-{lint,web,build,tools,e2e-types}.log`，Vitest JSON为`oq-fix03-vitest.json`。后端/浏览器使用确切提交归档：

```bash
python tools/linux_test_runner.py --source e19f8cfc6727fe5fd773a8c92ffc00d45eadfe93
python tools/local_browser_runner.py --source e19f8cfc6727fe5fd773a8c92ffc00d45eadfe93 --with-assets --scenario oq-demo-01
python tools/oq_demo.py --profile oq-demo-01 --mode inspect
python tools/oq_demo.py --profile oq-demo-01 --mode resume
```

- e19f8cf隔离浏览器run `cadffacac2fc45e8bd06178bbb24b2bf`：原六条加完整十四场景共7/7，单worker/零retry/零skip；正式scanner provision和真实资产预检通过，cleanup/processes_stopped均true。原始证据`local-evidence/oq-demo-fix03/fix01-regressions.json`、`fix03-legacy-checkpoint.json`，精简核对为`fix03-verification.json`。
- A类实际证据：`checkpointHasDefectCompletion=false`、`checkpointHasResolutionId=false`、`checkpointHasGlobalCompletion=false`，`proofReads=1`，两次解决周期，`actionsExecuted=0`、`backfills=0`、`writes=[]`，before/after完全一致，checkpointUnchanged=true，冲突原因是旧检查点无法确认原周期、服务端存在多个解决周期。
- 同一隔离项目验证合法首RESOLVED、该周期内内容编辑、RUNNING复测均可继续且不补完成标记；REOPENED终态成功后可以只读补齐原单周期完成身份。原完整marker检查点的人工新周期拒绝、当前周期选择与导航正确Attempt回归保留。
- e19f8cf持久v1.1 inspect只读、连续两次正常resume通过；前后完整业务快照相等，SHA256 `e06dfca1ff530f9469f9ce0b056e130be3e83cd2d92deaed26153a6a1def9d5b`，14实例/16Attempt/29模拟签署，原FAIL、两个未签署复测及CLOSED/REOPENED各一项不变。日志`oq-fix03-resume{1,2}-e19f8cf.log`及`oq-fix03-business-{before,after}.json`。没有在该持久项目制造故障。
- e19f8cf Linux run `3cd481916cbf40c2a66df38f18ee48f6`：Java21单元107、专项82、完整集成226，全部零失败/错误/跳过；真实扫描及既有报告门禁通过，exit=0、cleanup=true。原始报告在`local-evidence/linux-tests/3cd481916cbf40c2a66df38f18ee48f6/`，不引用旧轮次通过作为本轮结果。后续仅文档收尾，最终CI按其head和实际checkout另记。

`git diff --check`、Trail build/check已执行。Trail仍对main比较范围内已接受的网格reset报l-494264d8词法冲突；FIX03没有修改网格或运行读取锁，原解释与回归保留，理由继续写入提交和PR。不改写旧learning或弱化门禁。

## CI及限制

最终正常dev push/PR CI、实际head/base/merge-ref checkout和原始制品集中更新在[PR #13](https://github.com/fangzhiy/Test365Alm/pull/13)。若首次仅字体DNS失败，保留原始证据，对同一最终提交的失败浏览器Job最多有界复跑一次，记录attempt；成功Job不手动重跑，不改Playwright零retry、字体安装或报告门禁。再次失败即保留BLOCKED/NOT_RUN，不循环重跑。

旧a67be50的两路浏览器仍是DNS阻塞、NOT_RUN且无最终CI PDF。打印实现未变。本轮正常本地流程生成的`output/pdf/oq-demo-01-fix03/`样张来源为e19f8cf及上述browser run：普通/原FAIL/复测/汇总/偏差5/6/6/7/6页；已核对合成标识、来源、中文及首末页，不冒称重新逐页审核或新CI制品。演示操作沿用[FIX01说明](OQ-DEMO-01-FIX01.md#演示操作)。新增成果只集成dev/codex-main，不合main，停在独立审核点。
