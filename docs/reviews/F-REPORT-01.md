# F-REPORT-01 技术交付

基线 `d7dca900f868aa20a1709272f68f1260a7cbebf6`。产品/测试组合版本 `9be934e9cd41f7bc8faac9885f62bda52412b67c`；后续状态文档不冒称本地重新测试。最终交付与 CI 状态集中在 [PR #12](https://github.com/fangzhiy/Test365Alm/pull/12)，只集成 dev，main 等待独立审核，G/A03 未启动。

## 实现与使用

- 运行详情选定当前或历史已完成 Attempt →「查看所选 Attempt 执行报告」。接口路径同时绑定 project/run/attempt，未完成返回明确拒绝。原 manifest、原用例修订业务字段、步骤事实、执行时间、冻结附件版本和指定修订复核均独立读取。
- 选定测试集 →「测试集汇总报告」。先按 started_at/runId/attemptId 降序选每实例最新一次，再筛选；六类互斥。完成率含 PASS/FAIL/BLOCKED，通过率分母为已完成，零分母为 —；其他历史活动尝试另计。相关偏差数明确为全测试集关系范围。
- 项目偏差工作台 →「偏差清单报告」。服务端执行状态、严重程度、标题字面关键词及可选测试集筛选。来源与复测 EXISTS 去重，项目范围保留手工偏差，原 FAIL/BLOCKED 与各 resolvedRevisionId 周期复测分开。

所有报告先授权、设 RLS，再在 REPEATABLE_READ 只读事务中组装；ADMIN/MEMBER/VIEWER 均可读，不写业务、审计或 Outbox，不创建签署挑战。模拟签署使用原 payload/contentSnapshot/contentDigest，不采用当前 wrapper 版本冒充签署时值；整份动态报告不称为已电子签署。当前姓名/项目名称/偏差状态是生成时补充。无新迁移、依赖升级或认证实现修改。

完整响应上限为 1000 匹配实例、500 偏差、单子集合 5000 行、8 MiB JSON；JDBC 游标和解析前字节检查限制缓冲，超限整体拒绝并提示缩小范围。页面与打印用同一已加载 DTO，加载失败不可正式打印，作用域更换及卸载取消并丢弃迟到响应。附件仍走鉴权下载，不含存储地址。

## 实际验证

后端/工具实际被测 `1bbec573eb7bdf72b8fbf3790a13333f340f5460`，与交付版本对应目录 `git diff` 为空；前端/真实浏览器实际被测 `9be934e9cd41f7bc8faac9885f62bda52412b67c`。文档变化不冒称重跑：

| 命令 | 结果/证据 |
|---|---|
| Java21 `mvn -B -ntp -Pintegration verify -Dit.test=ReportDatabaseIT` | 107 单元 + 6 报告数据库专项，零失败/错误/跳过；`local-evidence/report-targeted-final.log` |
| `python tools/linux_test_runner.py --source <上述完整 SHA>` | 后端源 1bbec573，运行 `ddb00f0245734f928c78e95ca4133a11`：scan107/1 → unit107 → focused107/82 → full107/225 全部零失败/错误/跳过，退出 0、cleanup true |
| `npm run lint`、`npm run test:run -- --reporter=verbose --reporter=json --outputFile.json=../../local-evidence/f-report-vitest-verified.json`、`npm run build` | lint 退出 0，9 条 warning（含报告读取 effect 的 1 条，其余已有）；458 前端测试零失败/跳过，构建通过 |
| `python -m unittest discover -s tools/tests -v` | 214 项通过，`local-evidence/f-report-tools-release.log` |
| `python tools/local_browser_runner.py --source <上述完整 SHA> --with-assets` | `d8c66d00d61c4703b51d3f064e61069f` 六条真实 Keycloak 业务流程全 passed，单 worker/零 retry/skip；真实存储/扫描/独立 scanner LOGIN 通过，cleanup 与 processes_stopped 均 true |
| `git diff --check`、`trail build`、`trail check` | 已执行；最终提交前再次检查。原六 Job、原测试和门禁保留，CI 增加报告数据库/完整浏览器门禁及 PDF 制品；触发器仍为 push dev/main、无过滤 pull_request |

新增数据库回归覆盖原 FAIL 后修订/指派/重跑 PASS、稳定并列与历史活动尝试、55 个同用例不同修订实例、空集/零分母、手工偏差与真实关系/旧复测周期、原复核及三类签署绑定、VIEWER/跨租户/跨项目/撤权/停用拒绝。新增前端回归另覆盖“用例审批已签署但本 Attempt 未签署”的明确提示及键盘焦点/Escape/原页面恢复；覆盖确切范围校验、接口失败禁用打印、迟到响应丢弃、中文/特殊字符安全输出及超过默认页的完整显示。

开发中真实测试暴露并修正了运行当前状态混入固定事实的问题；夹具中的非法终态更新时间、非法状态边和停用字段也已改为遵循现有契约。首轮浏览器五条既有流程通过，报告流程在最后撤权夹具失败（空角色 PUT）；改用已有带版本 DELETE 后两轮六条均通过，没有 retry/sleep/削弱断言。早期完整 Linux 组合 `367e404` 的 107/225 全绿仅作对照，不冒称最终版本。

首轮 PR CI [42965558911](https://github.com/fangzhiy/Test365Alm/actions/runs/42965558911)（pull_request，源 1bbec573）六 Job 全绿；下载制品核对为 107 单元、225 集成、456 前端、17 完整浏览器均零失败/错误/跳过，另有独立项目 UI。后续两条前端边界回归已补齐为 458；最终提交正常触发的 CI 结果以 PR Checks 和交付摘要为准，无手动反复重跑。

## PDF 与边界

本地 `output/pdf/`：`run-report.pdf` 12 页、`test-set-report.pdf` 6 页、`deviation-report.pdf` 4 页，三个同名 PNG 是关键页面截图。来自最终真实认证/数据库/附件流程中的明确合成演示数据。逐页渲染位于 `local-evidence/f-report-pdf-pages-verified/`，文本/字体/末页检查位于 `f-report-pdf-validation.json` 与 `f-report-pdf-content-check.json`；中文字体已嵌入、长结果跨页、表头重复、末页完整，32 实例全部出现，12.5%/50.0% 口径正确。原始观察文本在正文、来源与原签署快照中的 245 次出现均完整保留。

技术排版已检查；业务方格式认可尚未取得。报告不持久化、重新获取可反映新的补充状态；大范围需缩小筛选。仅模拟签署，不代表真实电子签名厂商或合规认证。AUTH-FIX-01 修复及回归保留；历史登录失败逐例归因仍不确定，授权起点诊断头较晚仍为非阻塞限制。

下一轮 G/A03 仍需明确恢复授权、真实十四用例及预期结果/执行资料、业务角色与复核/签署安排，以及业务方报告格式确认；合成 PDF 和本轮报告流程不能替代真实 A03 验收。
