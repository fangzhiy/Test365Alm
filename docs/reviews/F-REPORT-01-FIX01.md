# F-REPORT-01-FIX01 交付记录

基线 `a4cec42ec940507be257235383bd0064eed994b4`，沿用 [PR #12](https://github.com/fangzhiy/Test365Alm/pull/12)。只同步 dev，main 保持 `d7dca90` 等独立审核；G/A03 未启动。最终交付提交及正常触发的 CI 链接见同一 PR 的集中交付摘要。

## 修正与证据

- **有效解决周期**：原历史指针在重开后有意保留，旧展示却把相等的历史 PASS 当作当前周期。保留原字段，报告查询统一提供最近解决修订和仅 RESOLVED/CLOSED 有效的 `currentResolutionId`；运行/偏差报告共用展示。真实数据库回归依次停在 REOPENED、IN_PROGRESS、再次 RESOLVED 和新周期 PASS，核对旧指针与历史结果保留。
- **相关关闭签署**：运行查询原已取得关系签署但返回时丢弃。现在按原记录 ID 去重、稳定排序合并，仍受 5000 记录及 8 MiB 总响应限制。A FAIL → D → B PASS → D 关闭签署 S 的真实数据库测试核对 A/B 原 payload 与 S 完全相等，双方未被额外运行签署；同项目无关及另一项目/租户签署不混入。读取前后签署、挑战、审计及各 Outbox 数量不变。
- **失权宿主清理**：报告拒绝原仅清空自身。新增失权回调接入执行 `invalidateScope` 和偏差 `onDenied`，取消宿主请求并清空列表/详情；报告丢弃迟到响应。普通读取失败保留普通错误语义。组件验证成功加载后拒绝、项目切换、卸载及迟到响应。

DTO 增加针对性范围/必需字段/合法历史 null、唯一 ID、分类互斥、计数及比率一致性校验；覆盖 FAIL 明细却 PASS=1、比例超过 100%、身份错配及必需记录缺失。错误响应不展示“完整加载”，不能打印。

正文拆出业务组件与格式函数，中文状态/范围、测试集授权名称选择、最近/有效周期分开展示。签署默认展示冻结姓名、含义、时间、对象修订和摘要，原快照仍可查看，只有显式勾选才打印附录。时间统一 UTC，无重复 Z UTC。短字段与值保持同页，统计集中；长步骤/描述仍可跨页，不截断内容。

浏览器暴露刷新旧历史按钮可取消新历史请求；受控回归先失败，后锁定保留旧内容的刷新操作，同时保持普通运行切换可中断及迟到响应保护。选择历史详情期间不提供旧报告入口。原审计刷新仍跟踪独立步骤版本；trail 对新只读夹具的“仅 Attempt 版本”提示为词法误报，未修改审计刷新策略及其原回归。

## 实际验证与版本

| 命令 / 被测版本 | 实际结果 |
|---|---|
| Java21 `mvn -B -ntp -f apps/server/pom.xml -Pintegration verify -Dit.test=ReportDatabaseIT` | 修复前 `f-fix01-red-corrected.log`：107 单元通过，7 集成中 2 失败；修复后专项 107/7 零失败/错误/跳过 |
| `python tools/linux_test_runner.py --source aac98a1ce3db02b9d22952b2f167018807ee23da` | run `3f8a445a686b4421bbd00317cf819bf9`，scan107/1、unit107、focused107/82、full107/226，全部零失败/错误/跳过，cleanup true；后续后端/tools 无变化 |
| `python -m unittest discover -s tools/tests -v` | 214 通过，`local-evidence/f-fix01-tools3.log`；正式库存同步新增数据库用例，原门禁保留 |
| `npm run lint`、`npm run test:run -- --reporter=verbose --reporter=json --outputFile.json=../../local-evidence/f-fix01-vitest-resumed.json`、`npm run build` | 产品源 `92d561253b9dba091c810d5967ad2e072c667587`；lint/build 退出 0，lint 10 warning（含既有警告），467 测试全通过；日志 `f-fix01-*-resumed.log` |
| `python tools/local_browser_runner.py --source 9fa0b549b729b67ae0ab5fa447de9bad2822af3c --with-assets` | run `c57092b3ce7a460f8f57bf3fd7140d70`，六条真实业务流程全通过，1 worker / 0 retry / 0 skip；真实存储、扫描、独立 scanner LOGIN 均通过，cleanup/processes_stopped 均 true |
| `git diff --check`、`trail build`、`trail check` | diff/build 通过；check 有 1 项上述词法误报，已在提交和本记录解释，无其他阻断项；push 前再次执行 |

历史验证失败保留：前三轮真实浏览器原五流程通过，报告流程分别超时于历史选择；安全检查点与受控测试证明刷新取消竞态，未加 retry/sleep/超时、未删断言。最近一次前端全套 466/467 的失败也保留，原因是锁定过宽阻止原运行切换，收窄后 467/467 通过。更早 RED 的一次夹具表名错误不作为产品 RED 证据。

后续 `34800ac9860d4ca5acc58177b543941e` 已通过历史运行报告及签署验证，卡在新增普通长度样例：`has` 定位器错误包含对话框祖先，无法匹配表格行内按钮。改为行相对按钮定位并校验唯一性后，上述最终真实流程全通过。没有改产品等待上限。

## 新样张

实际被测源 `9fa0b549b729b67ae0ab5fa447de9bad2822af3c`，`output/pdf/` 下：`run-report.pdf` 12 页、`test-set-report.pdf` 12 页、`deviation-report.pdf` 8 页、`run-report-ordinary.pdf` 3 页；同名 PNG 为实际页面截图。三类及普通样例均标识合成演示数据。逐页 PNG / contact sheets 位于 `local-evidence/f-fix01-pdf-pages/`，结果为 `f-fix01-pdf-validation.json` / `f-fix01-pdf-content-check.json`。

已实际查看全部逐页缩略及关键大图，中文/表头/短字段同页/长文本跨页/末页/附件签署区域完整；六类数量与 12.5% / 50.0% 集中同页，实例 1..31 及原实例完整。默认无原始签署 JSON，保留正文和偏差来源中的 175 次原观察及偏差报告中的 70 次原观察、各 60 次长描述；原样保留安全转义字符。运行长样张末页仅含报告尾注、部分页面留白较多，是当前浏览器分页的版式限制，未通过裁剪业务内容减页。技术内容和排版验证通过，业务方格式认可待确认。PDF 检查用忽略目录中的 Pillow，不新增项目依赖。

## 边界

没有改认证、状态机、数据库历史、依赖、工作流触发规则或业务写权限。三类报告仍授权/RLS/一致性只读，无持久档案；重新生成可反映当前补充状态。仅模拟签署，历史关闭签署不代表当前修订/状态已签署。无实时撤权推送，不承诺撤回已下载 PDF。

样张为合成演示数据；技术排版检查与业务格式认可分开，业务格式认可仍待确认。历史登录失败逐例归因仍不确定，授权起点诊断头较晚为非阻塞限制。真实 G/A03 业务材料及明确恢复授权留给后续验收。
