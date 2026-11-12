# OQ-DEMO-01 合成十四用例完整演示

## 已接受的 F 收口

PR #12 head `dd62943f9f341226e1492f9b032dd29467e9ebc3` 两路六 Job 成功、无新增代码后，以确切 head 合入 main：`b8975e4604295269c2c0e8efef461614fc4b37da`。dev/codex-main 从该合入点快进。F 技术验收已接受；业务报告格式认可待确认，分页留白及历史登录逐例归因限制保留。合入正常 push CI：[main 42998297752](https://github.com/fangzhiy/Test365Alm/actions/runs/42998297752)、[dev 42998350475](https://github.com/fangzhiy/Test365Alm/actions/runs/42998350475) 均六 Job 成功，不手动重复全集。

## 新增合成场景（待审核）

定义：[oq-demo-01-v1.1.json](../../tools/scenarios/oq-demo-01-v1.1.json)，SYNTHETIC，十四个稳定 OQD 编号，复用旧 demo_seed 的十四种 SAP 示例及步骤。实际观察明确未操作 SAP。作者/执行人 MEMBER、指定复核人 ADMIN、VIEWER 只读；不增加权限模型。

十四个绑定修订先提交/批准及真实 Keycloak 再认证后模拟批准签署，再创建实例、分配负责人、执行。OQD-01 展示拒绝→新修订→批准；OQD-14 暂停/继续。十四个原 Attempt 为12 PASS/2 FAIL，全部有运行完成模拟签署；OQD-12/13 各创建真实失败来源偏差及独立 PASS 复测。OQD-12 关闭并模拟签署，OQD-13 保持 REOPENED、旧 PASS 仅历史。实例最新结果14 PASS，历史16 Attempt 为14 PASS/2 FAIL，不能宣称所有偏差已解决。复测 Attempt 未补签，原失败不覆盖。

步骤附件真实上传→扫描→CLEAN 发布→关联v1，再发布v2；旧报告保留v1。签署通过既有弹窗再认证与显式确认，未插入签署/Proof。业务准备与部分流转采用真实 CSRF/OIDC API；批准签署、原始步骤执行/暂停继续、运行及关闭签署、实例导航、报告与打印实际操作 UI。审计历史通过既有查询/组件核对，签署证据来自原 signature_record，不把签署存在写成对象审计页包含签署事件。

实例网格新增只读“修订与闭环进度”，按实例绑定修订读原内容/复核/批准签署，提供确切最新 Attempt 报告和测试集真实偏差入口。写操作仍在原工作台；不硬编码场景 ID，不扩展通用工作流。取消及晚到响应回归覆盖范围切换。

## 安全启动、查看、续跑与停止

仓库根执行：

```bash
python tools/demo_up.py --profile oq-demo-01
python tools/oq_demo.py --profile oq-demo-01 --mode inspect
python tools/oq_demo.py --profile oq-demo-01 --mode resume
python tools/demo_down.py --profile oq-demo-01
```

打开 http://127.0.0.1:15273 ，使用 r03-user / r03-member / r03-viewer（秘密仅 `.env.demo-oq-demo-01`），选演示租户及 `SYNTHETIC · OQ-DEMO-01 十四用例完整演示`，选择目录 `SYNTHETIC OQ-DEMO-01 v1.1` 下同名测试集。从实例网格“修订与闭环进度”及“进入运行”开始。Root/Subject 未归档列表不显示已归档对象，须先点目录。

新环境固定独立 project/端口/配置/卷，旧 demo 环境、凭据、HBA 与数据不改。正式 scanner provision 创建独立 LOGIN 并验证真实双 SCRAM；不是仅看服务健康。只读启动/inspect 核对检查点记录对象 ID 与当前服务端状态；显式 resume 才推进未完成动作。发现重复对象、手工修订/归属冲突拒绝覆盖；分页完整读取，完成动作按长期业务状态发现，不依赖短期幂等记录。旧演示首次11/2/1检查保留，完成检查点重启改为只读实际状态。

## 验证和材料

开发 RED：旧完成检查点遇合法新 RUNNING Attempt 抛固定结果分布错误；修复后只读输出实际2 RUNNING，不写业务。新增导航先因模块未实现失败，再通过绑定旧修订、拒绝错修订、失权及晚到响应测试。

持久环境完整场景通过；续跑读取既有业务并通过，未重复创建已完成动作。v1开发脚本错用 revisionNumber 导致未展示拒绝，现场及批准记录保留在独立原型项目，改为新v1.1项目，不回填历史；另保留不合法revision字段、附件typed参数、缺陷字段和UI过早断言的开发失败。最终采用确切协议字段及实际响应等待，不加retry/sleep或放宽原测试超时。

前端全套曾497/498通过：FIX02既有测试在初次详情仍busy时点刷新，捕获旧signal；补等待按钮enabled并断言受控Promise已启动，保留取消/晚到断言。工具首次219/220通过：新增浏览器必需用例后旧工具夹具未同步，已补新用例，原缺失/失败/重复拒绝门槛保留。最终组合验证、被测SHA与CI集中在新PR摘要；失败日志保留于 `local-evidence/oq-*`，不写未运行为通过。

证据：`local-evidence/demo-oq-demo-01/evidence-index.json` 逐条关联修订、复核、实例/Run/Attempt、签署原记录、固定附件、来源/复测及报告；`scenario.json` 为阶段检查点，`stages.json` 为阶段结果，`inspection.json` 为只读实际状态。一次性 runner 使用 `python tools/local_browser_runner.py --source <SHA> --with-assets --scenario oq-demo-01`，保留原六流程，新增第七流程；CI保留原全集，新增完整场景及原始JSON制品。

本地实际命令与证据：

- `python -m unittest discover -s tools/tests -v`：220/220；`local-evidence/oq-tools-final.log`。
- `apps/web` 执行 `npm run lint`、`npm run test:run -- --reporter=verbose --reporter=json --outputFile.json=../../local-evidence/oq-vitest-final.json`、`npm run build`：43文件、500/500，零失败/跳过；lint保留10项既有warning、build通过。此组合随后提交为 `cc14bfe7ff43e564b54fbd9a34178f163900ad3a`。
- `python tools/local_browser_runner.py --source cc14bfe7ff43e564b54fbd9a34178f163900ad3a --with-assets --scenario oq-demo-01`：7/7、1worker/0retry/0skip，正式scanner provision及真实资产preflight通过，cleanup/processes_stopped均true；run `3c0e82262e00418b850881f47d308643`。
- `python tools/linux_test_runner.py --source cc14bfe7ff43e564b54fbd9a34178f163900ad3a`：Java21单元107、专项集成82、完整集成226，均零失败/错误/跳过；含真实clean/EICAR扫描专项及既有报告门禁。run `2b473fc6fbdd492da1db95995133bd5f`，逐阶段JSON保留在`local-evidence/linux-tests/`；后续8f2仅浏览器续跑冲突保护，不改变后端/工具测试源。
- `python tools/oq_demo.py --profile oq-demo-01 --mode resume`：完整业务场景、既有状态续跑通过；持久环境正常停止/重启后inspect通过，数据保留。`8f2dba663e5e94ee4db60d1e9409964549c734f2` 仅收紧已完成目录/负责人变更冲突保护，其实际持久续跑也通过（`oq-demo-resume-safety.log`）。
- `git diff --check`、`trail build/check`通过（无冲突，20条词法提示已核对）。首次runner使用短SHA被入口拒绝、未创建资源，改用完整40位SHA后执行，未将拒绝写成通过。

最终提交含以上实现及简短证据收尾；最终head的正常dev push / pull_request CI、实际checkout与原始制品核对记录在同一PR摘要，本地不同被测版本不冒称最终CI结果。

样张及截图：`output/pdf/oq-demo-01/` 下 ordinary-run、original-fail、retest-pass、test-set-summary、deviation-list 的 PDF/PNG。本地v1.1来源页数依次5/6/6/6/6，已渲染检查全部29页、中文/跨页/表头/附件/签署/末页完整，保留既有留白与字段跨页限制，不在本批重设计打印。逐页缩略图和提取核对见 `local-evidence/oq-pdf-review/`。历史F样张来源不改写。技术排版通过不等于业务格式认可，模拟签署不等于正式电子签名认证。

真实 G/A03 仍暂停。缺口：正式十四用例原文/版本/步骤/预期；正式执行环境和资料；执行/复核/签署角色安排；业务认可报告格式；恢复真实验收的明确授权。本批不连接SAP/旧ALM/生产，不迁移、部署或接第三方签名。

## OQ-DEMO-01-CLOSE

2026-11-12。合成技术演示已独立审核接受，等待业务评审与真实 G/A03 输入。仅合入已审核内容及集中更新现行文档，无产品、依赖、测试或门禁改动。

### 合入与验证

- 已审核 head：`ec71d66dc8827d6455a895996109f223e6ccc77e`。PR #13 合并时以 sha 条件锁定；当时 base 为 `b8975e4604295269c2c0e8efef461614fc4b37da`，六 Job 必要检查成功。
- 实际合入 M：`5808d426a397da85b8d7152085a2cd32025e77b0`。父提交依次为上述 base/head；树 `bd7cb51729f8109ac29478598521856420a049f7` 与审核 head 相同。dev/codex-main 安全快进同步，无强推。
- M 的 main push [43153344580](https://github.com/fangzhiy/Test365Alm/actions/runs/43153344580)、dev push [43153415771](https://github.com/fangzhiy/Test365Alm/actions/runs/43153415771)：均 attempt1、六 Job 成功；原始制品 checkout 均为 M，非旧 PR merge ref。Java21 单元107、完整集成226、前端521、工具229、完整浏览器18、独立项目UI1，零失败/错误/跳过；报告门禁和正常资源清理成功。来源及原制品校验：`local-evidence/ci-prep/{run}/verification.json`。
- OQ 索引及 FIX03 真实旧文件回归同源核对：三个完成标记均缺失、proofReads=1、actions/backfills=0、writes=[]、检查点及完整前后快照不变。标准14实例、16Attempt、29模拟签署，原始12PASS/2FAIL、两个独立PASS复测未签署、CLOSED/REOPENED各一项、历史附件固定v1。
- CI-PREP-01-A 已关闭，字体准备/系统依赖/Chromium/完整业务与报告产出实际通过；FIX03 最终真实 CI 证据已补齐。旧 DNS 故障两路43057338879/43057350036的attempt1/2仍为历史失败/浏览器NOT_RUN，不改写。
- 本批本地仅执行只读 `python tools/oq_demo.py --profile oq-demo-01 --mode inspect`（14个最新Attempt均FINISHED/PASS）、文档检查、`git diff --check`、`trail build`/`trail check`；未重复本地Docker/浏览器全集，未运行resume或修改持久业务。用户工具配置、旧项目/卷/检查点保留。
- 本次文档提交后正常CI的最终结果只附PR #13交付记录，不为补编号再生成文档提交。

### 演示评审入口

项目 **SYNTHETIC · OQ-DEMO-01 十四用例完整演示**，测试计划/实验室目录及测试集均为 **SYNTHETIC OQ-DEMO-01 v1.1**。在仓库根执行：

```bash
python tools/demo_up.py --profile oq-demo-01
python tools/oq_demo.py --profile oq-demo-01 --mode inspect
python tools/demo_down.py --profile oq-demo-01
```

启动归属核验后的既有服务；inspect只读核对记录的对象；停止保留数据。浏览器 http://127.0.0.1:15273 ，账户为r03-user/r03-member/r03-viewer，秘密仅在忽略配置 `.env.demo-oq-demo-01`。检查点及本机材料在 `local-evidence/demo-oq-demo-01/`（以现有工具profile路径为准）；不要删除检查点或自动resume来恢复预设状态。详细逐步入口见 [FIX01演示操作](OQ-DEMO-01-FIX01.md#演示操作)。

最终已审核样张和十四条证据索引来自 [PR CI 43146324025 / artifact13912121671](https://github.com/fangzhiy/Test365Alm/actions/runs/43146324025/artifacts/13912121671)，head ec71d66、实际merge-ref checkout 1f8cee6d5905a70ed0a431730ab9962ea5e547e0。原ZIP、索引与PDF保留于 `local-evidence/ci-prep/43146324025/`：解包后 `extracted/local-evidence/r03/oq-demo-01/evidence-index.json` 和 `extracted/output/pdf/`。OQ普通/原FAIL/复测/汇总/偏差分别5/6/6/6/6页；F长运行/汇总/偏差/普通为12/11/8/3页。已审核逐页技术检查沿用，本批不重复生成样张；M的新CI制品单独核对来源，不冒称重新逐页审核。

建议从实例网格“修订与闭环进度”及“进入运行”查看：

1. OQD-01修订1被拒绝，新修订2重新批准及批准模拟签署；暂停/继续过程查看执行历史，终态不伪装为仍暂停。
2. OQD-12/13尝试1原FAIL保留，尝试2另记PASS复测，两个复测均未运行签署。
3. OQD-12 CLOSED及确切关闭修订签署；OQD-13 REOPENED的旧PASS仅属历史周期，不代表当前问题已通过。
4. OQD-01附件资产v2已发布，但历史Attempt仍固定v1。三种模拟签署分别核对确切用例修订、Attempt和关闭修订，不能相互替代。
5. VIEWER可读/打印，不可写；汇总最新14PASS与历史16Attempt分别解释。人工变更后的恢复冲突应保留现场人工核对，不能清空历史绕过。

### 待业务方确认与限制

真实G/A03继续暂停；正式十四用例原文/版本/步骤/预期、真实环境及执行资料、执行/复核/签署角色、业务认可的报告格式，以及明确恢复真实验收授权均待确认。不把合成观察当作SAP实际操作，不把模拟签署当正式电子签名认证；迁移、部署及第三方厂商对接未启动。历史登录逐例归因仍不确定；已接受的分页留白/尾注单页保留。技术验收不代替业务格式认可。
