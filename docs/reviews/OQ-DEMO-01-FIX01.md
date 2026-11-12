# OQ-DEMO-01-FIX01

本轮从待审 `a4f94c4044a0c3df597ccec4ae97e901640c2a48` 修复两项 P2，继续 [PR #13](https://github.com/fangzhiy/Test365Alm/pull/13)，只同步 dev/codex-main；main 保持已接受 F 合入提交 `b8975e4`。旧项目、卷、历史证据及用户工具配置保留。SYNTHETIC 合成演示和模拟签署不代表真实 SAP 操作、G/A03 验收或正式电子签名认证；报告业务格式认可仍待确认。

## 原因与修复

- 偏差恢复原来读取 DefectView 不存在的 `resolvedRevisionId`，并回退首条复测。现从已有授权报告读取历史解决指针，以完整分页的不可变修订历史交叉核对进入 RESOLVED 的周期；RESOLVED 内容编辑不被当成新周期。只选择确切周期的复测，没有时不借用旧 PASS。已完成身份保存偏差、终态修订/版本、解决周期及复测关系；每次 resume 在业务动作前只读重验。v1.1 完成检查点仅在终态、唯一原周期及确切 PASS 均可确认时补充本地字段，否则报具体冲突并保留现场。终态成功但本地标记丢失时先查服务端恢复，不依赖短期幂等键。
- 网格原来保存点击时的整个实例。现仅保存范围与实例 ID，导航从当前 instances 解析执行状态/Attempt；移除、筛选/范围改变或失权即关闭并取消读取，行重新出现不会自动恢复。RUNNING/PAUSED 不提供旧 FINISHED 的“最新报告”；历史入口仍在运行工作台。绑定的用例修订、复核与批准签署不变；四种执行状态分别说明下一步，PASS 与三种签署/偏差关闭分开。

## RED/GREEN 与真实验证

修复前提取原选择逻辑、不改变行为后运行回归：历史 PASS 被误选，导航更新后仍提供旧报告，移除实例仍保留请求，合计3项失败（`local-evidence/oq-fix01-red.log`）。修复后专项15项通过，涵盖当前周期、旧周期单独存在、RESOLVED 内容编辑、完成身份变化、网格宿主状态推进/刷新/筛选/范围/失权及迟到响应；原绑定修订、错修订拒绝和失权回归保留。

真实浏览器扩展嵌入原十四场景用例，但使用另一独立项目 `SYNTHETIC-OQD-FIX01`：正式 OIDC/CSRF API 创建失败来源→解决→PASS复测→重开，恢复完成标记；用户再解决到新周期后调用同一恢复保护，必须报冲突、无写请求，偏差/修订/复测/审计/Attempt/签署查询前后相等。随后验证旧 PASS 不能代替当前复测、RESOLVED 内容编辑不改变周期。独立实例导航保持打开，刷新 RUNNING、PAUSED、FINISHED 后实际点击报告，核对请求的确切 runId/attemptId。标准演示的14实例/16Attempt/29模拟签署不受这些故障夹具影响。隔离证据为浏览器制品的 `oq-demo-01/fix01-regressions.json` 和 `fix01-navigation.png`。

本地被测版本及命令：

- `14001611e25e44463f22bd94c7a2c9a16471d562`：`python tools/linux_test_runner.py --source <完整SHA>`，Java21单元107、专项集成82、完整集成226，零失败/错误/跳过；真实CLEAN/EICAR及既有报告门禁保留，run `1d39d077f6cf4cf5acc5055e71e1ec2e`，cleanup=true。后续只有前端、浏览器和说明修改，不冒称该次本地Linux验证来自最终head。
- `ee6224fd96aa1aba289027efde1a4979de995703`：前端 `npm run lint`、`npm run test:run -- --reporter=verbose --reporter=json --outputFile.json=../../local-evidence/oq-fix01-vitest-final.json`、`npm run build`；45文件511测试全部通过、零失败/跳过，lint仅10项既有warning。另以 `npx tsc --ignoreConfig --noEmit --skipLibCheck --target es2023 --module esnext --moduleResolution bundler --types node e2e/oqFixRegressions.ts e2e/oq-demo.spec.ts` 核对新增浏览器代码类型。
- 最后代码提交 `931ee486deecc3be646d42c108695c9846b61787` 补齐“无旧标记时终态内容编辑不能补认”的RED/GREEN（`oq-fix01-legacy-edit-red.log`、专项16通过的`oq-fix01-green-final2.log`）。同样的lint/完整test/build命令再次通过：45文件512/512、零失败/跳过、仅10项既有warning；证据为`oq-fix01-*-931ee48.log/json`。该提交再次真实持久resume通过，API业务快照仍与1400161续跑前完全相等（SHA256 `e06dfca1ff530f9469f9ce0b056e130be3e83cd2d92deaed26153a6a1def9d5b`）。后续提交仅交付文档和Trail原因记录。
- Python `python -m unittest discover -s tools/tests -v`：220通过（`oq-fix01-tools.log`），本轮未改Python工具。针对性RED/GREEN分别见`oq-fix01-red.log`、`oq-fix01-green-final.log`；宿主刷新追加验证55项通过，见`oq-fix01-nav-refresh.log`。
- 持久v1.1在1400161执行`python tools/oq_demo.py --profile oq-demo-01 --mode resume`，只读核对补齐旧完成身份后通过；再次resume前后逐项业务快照一致（`oq-fix01-business-before.json`与`after.json`），14用例、16Attempt、29签署及CLOSED/REOPENED保持。现有inspect/目录/负责人保护回归保留；未用该持久项目制造新周期故障。
- `python tools/local_browser_runner.py --source ee6224fd96aa1aba289027efde1a4979de995703 --with-assets --scenario oq-demo-01`：原六流程及扩展十四场景共7/7、1worker/0retry/0skip。run `9eede75b7803420c94ee40f0d9bad7ac`，真实资产preflight/正式scanner provision通过，cleanup和processes_stopped均true。`local-evidence/oq-demo-fix01/fix01-regressions.json`保留实际before/after快照、零写请求、冲突文本和导航实际报告路径；`fix01-progress.json`为complete。该次标准场景首次完成保持14实例/16Attempt/29签署，额外故障项目单独计数。

本轮正常浏览器样张已复制到`output/pdf/oq-demo-01-fix01/`，同名PNG及`fix01-navigation.png`保留。普通/原FAIL/复测/汇总/偏差页数依次5/6/6/7/6（源ee6224f、run9eede75；不是上一轮5/6/6/6/6）。已核对合成标识、来源索引、中文、首末页和内容，首末页渲染及来源计数在`local-evidence/oq-demo-fix01/`；打印实现未改，完整逐页排版审核沿用既有基线，不把该次首末核对冒称重新逐页验收。最终收尾另增加复测结束必须FINISHED/PASS才追加流转的断言；最终正常CI验证这条组合，不冒称它已包含在ee6224f本地运行中。

隔离浏览器实际开发失败保留：1400161为6通过/1失败，新增夹具误用不存在的`/rerun`；de31433仍6通过/1失败，安全阶段停在`navigation-new-attempt`，揭示宿主同筛选刷新会暂时清空行而关闭导航。已改用正式`/runs/{id}/attempts`及保留刷新身份/暂禁报告的语义，未提高超时或删除断言。两次失败都正常清理。另一次误传不存在的SHA在`git cat-file`入口拒绝、未创建资源，记为未运行。

Trail build/check已执行。check对TestExecutionPanel的`setPending('grid-read')`报l-494264d8词法冲突：本改动保留pending、交互锁、取消与轮次保护，且刷新期间禁止当前报告，未恢复旧的无锁历史刷新。理由已写入实现提交和PR，原历史刷新/晚到切换测试保留；没有改写旧learning或降低检查规则。最终head的正常dev push / pull_request CI、实际merge-ref checkout与原始制品结果集中在同一PR摘要，不把旧版本本地结果写作最终head。

## 演示操作

仓库根运行：

```bash
python tools/demo_up.py --profile oq-demo-01
python tools/oq_demo.py --profile oq-demo-01 --mode inspect
python tools/oq_demo.py --profile oq-demo-01 --mode resume
python tools/demo_down.py --profile oq-demo-01
```

`up` 启动归属核验后的独立持久服务，不推进业务；`inspect` 只读显示已记录对象的实际状态；显式 `resume` 先核对检查点及服务端状态，再继续可确认的未完成步骤；`down` 正常停止服务并保留数据。凭据仅在忽略的 `.env.demo-oq-demo-01`，不写入说明。

1. 打开 http://127.0.0.1:15273 ，登录 r03-user（复核人/admin）、r03-member（作者/执行人）或 r03-viewer。选择项目“**SYNTHETIC · OQ-DEMO-01 十四用例完整演示**”。测试计划/测试实验室先选择目录 `SYNTHETIC OQ-DEMO-01 v1.1`，再选同名测试集。
2. 网格 OQD-01 点击“修订与闭环进度”，核对绑定修订2、指定复核人、批准与模拟签署；审计历史可见拒绝和新修订批准。在测试用例入口选择该用例及修订1，可看原拒绝记录，不给旧修订补批准。
3. OQD-12/13 点击“进入运行”，选“尝试1 · FAIL”查看原失败结果；选“尝试2 · PASS”查看独立复测，点击“查看所选 Attempt 执行报告”。两个复测均未运行签署，相关偏差关闭签署不代表复测已签署。
4. 从导航“本测试集真实关联偏差及处理结果”或偏差工作台打开清单/详情。OQD-12 为 CLOSED，查看其确切关闭修订的模拟签署；OQD-13 为 REOPENED，其 PASS 只属于历史解决周期，不表示当前问题已通过。不要只看实例14 PASS就判断全部偏差已关闭。
5. OQD-01 原 Attempt 报告的附件清单固定为证据v1；资产入口查看同一资产已有v2，旧执行关联不会更新。签署展示分别核对用例修订、运行Attempt和关闭修订的身份、时间、摘要与原快照。VIEWER可读取打印，不能写业务。
6. 汇总报告核对最新14 PASS；历史为16 Attempt、14 PASS/2 FAIL。若人工推进了已完成偏差周期，resume 会提示“演示恢复冲突…保留现场，不追加流转”，停止相关写入。保留记录交由人工核对；不要删除检查点、清空历史或重建项目来消除提示。

报告渲染未改，不重新设计打印；新浏览器流程正常生成的PDF按实际源提交核对，已有逐页排版审核作为格式基线。分页留白、历史登录逐例归因及业务格式认可限制保留。真实 G/A03 仍暂停，恢复需要正式十四用例/版本/预期、环境资料、执行复核签署角色、业务认可格式与明确授权。
