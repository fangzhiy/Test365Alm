# F-REPORT-01-FIX02 交付记录

审核基线 `06fda36b28d4bf57df0109b69f7bff91bd8be699`。沿用 codex-main/dev 和 [PR #12](https://github.com/fangzhiy/Test365Alm/pull/12)，只同步 dev，main 保持 `d7dca90` 待独立审核，G/A03 未启动。最终交付 SHA 与最终 CI 链接集中在同一 PR 摘要。

## 原因与修正

安全过滤器返回 code-only `UNAUTHENTICATED` / `FORBIDDEN` / `CSRF_REJECTED`，共享解析器却要求 message 同时存在，回退成 HTTP_401/403；报告分类遇到字符串 code 后不再参考 status，漏掉真实失权。这是后端响应格式与前端解析/分类契约不一致。

`projectAccess.parseError` 保留非空 code、HTTP status，缺少 message 使用安全本地提示；无效/非 JSON 不展示上游原始文本。`reportScopeDenied` 对已确认的 HTTP 401 保底清理，包括 HTTP_401；其他状态需要明确作用域拒绝码。未知 403、CSRF、报告参数/范围/数量错误、服务和网络错误仍清空报告并禁印，但不清理宿主。共享调用范围已核对，原结构化错误及网络异常行为保留，前端全集覆盖其既有调用者。

未改 FIX01 周期/关闭签署/DTO/刷新锁，以及认证、RequestCache、CSRF、权限/RLS、状态机或签署实现；无新迁移、依赖、打印布局、retry/sleep/超时放宽。

## 证据与验证

- 修复前 `npx vitest run src/projectAccess.errors.test.ts src/DefectsPanel.test.tsx src/TestExecutionPanel.test.tsx`：73 项中 15 失败、58 通过，`local-evidence/f-fix02-host-red-valid.log`。原始 Response 穿过实际 requestJson/parseError/loadReport，两个宿主均成功加载后无法清理；另验证无效/空/非 JSON 401 的漏判。更早测试夹具漏了 filters.testSetId 或被附件请求消耗的失败不作为宿主产品 RED 证据。
- 修复后同一专项 73/73 通过（`f-fix02-focused-green.log`），随后扩充普通错误对照，完整前端 496/496 通过。两个宿主均覆盖原始 401/403 拒绝、报告消失/无法打印、列表详情清空、相关 AbortSignal 中止、迟到详情/测试集名称不复活；关闭普通错误报告仍保留宿主。原报告迟到/项目切换/卸载及运行切换测试保留。
- 产品/测试源 `4506f1fd4d21247cd946c61c43e7cd3c46ee3515`；前端实际运行 `npm run lint`、`npm run test:run -- --reporter=verbose --reporter=json --outputFile.json=../../local-evidence/f-fix02-vitest-finalcode.json`、`npm run build`，均成功，496 项零失败/跳过，lint 10 个既有警告。第一次 build 的新测试异常类型缺失已补齐，失败日志保留，不把它写成产品回归。
- `python -m unittest discover -s tools/tests -v`：214 通过，`f-fix02-tools-final.log`。首次被上一轮忽略目录临时 PR Markdown 的相对链接绊住，改为临时文本扩展名后通过，未改工具或门槛。
- Linux 本地 `python tools/linux_test_runner.py --source 4506f1fd4d21247cd946c61c43e7cd3c46ee3515`：run `f4b76e4ae68346bcbdc635fff16625fe`，Java21 scan107/1 → unit107 → focused107/82 → full107/226，全阶段零失败/错误/跳过，退出0、cleanup true。此后后端/tools 无变化。后端单元与完整 Linux 集成均为本轮实际执行，不引用 FIX01 冒充重跑。

浏览器开发失败保留：源4506f1f/run b63ae5d4b5e34fd6bc2c102bb49e6365 的五条既有流程通过，新增阶段未通过，测试误把退出协议写成204；源0ee6221/run4284c593f5b24103a5ecd5618316ba46 核对到退出200，但仍未到达报告401检查点。随后完整核对服务端发现成功体为 `{status:LOGGED_OUT}`、错误体才用 code，并修正成功体断言；本地退出保留 IdP SSO，恢复登录也改用正常跳转而非强求用户名表单。没有把这两轮写成会话失效链通过，清理均成功。

源eb132d8/run793f90bbc64c46e3bcb0c8cee29f226a 的安全检查点进一步定位到退出200之后额外的浏览器 Response body 读取未完成。最终使用退出200 + 第二标签实际未登录入口，保留原标签报告 GET 必须401/宿主清理，并用同 Cookie 上下文另一次真实 HTTP GET 核对原始 code-only 401 体，不是重试或伪造响应。源2854d52/runecba08de6ab24fa185d81aae002479d3 因临时端口20220竞争，后端未启动，浏览器业务 NOT_RUN，清理成功；不停止占用进程，用新受控资源继续同SHA验证。

`git diff --check` / `trail build` 已执行。trail check 有两项只读测试夹具的词法匹配：原审计刷新仍含独立步骤版本；旧浏览器失败保留直到确切新版本验证，均未违反对应 learning，提交及 PR 摘要说明这一判定。原 learnings 不改写。

最终本地浏览器：`python tools/local_browser_runner.py --source 2854d52f365342c48cefa4ca0f153fe8cd879da4 --with-assets`，run `b7b282229ce64bfd9038cb9eeda9259c` 六条真实流程全部通过，1 worker/0 retry/0 skip，真实扫描/附件/独立 scanner LOGIN 通过，退出0、cleanup/processes_stopped true。安全检查点依次证明退出200、第二标签未登录、原标签未提前清空、报告真实401/UNAUTHENTICATED、报告及宿主清理；后续原成员撤权及正向关键词筛选均通过。证据为 `local-evidence/f-fix02-browser-fresh-port.log`、对应 run 的 results.json 和 repo/local-evidence/r03/reports-context.json。本轮最终正常 CI 会运行原17条全集；其实际 source、checkout、制品计数和链接在 PR 集中摘要记录，和上述本地六流程分开。

新增真实浏览器阶段复用原报告流程：先加载列表/详情/报告，同会话第二标签使用 UI 真实 CSRF 退出，原标签不刷新、不提前清空；应用报告筛选，核对真实安全过滤器 HTTP 401 及原始 `{code:UNAUTHENTICATED}`，再验证报告/打印入口及宿主列表详情清空。验证之后才重新登录，继续保留原成员撤权场景。正向标题关键词先命中目标并排除不匹配偏差，另保留空结果，再清空筛选生成完整 PDF。原六条本地流程/17 条 CI 全集及自动门禁不删减。

## 样张与限制

本轮未改打印实现，引用 FIX01 已完成的逐页审核：PR CI [42986595142](https://github.com/fangzhiy/Test365Alm/actions/runs/42986595142) 制品为运行12/汇总11/偏差8/普通运行3页；FIX01 本地为12/12/8/3页，属于另一份样张。初版 F 的12/6/4页保留为历史来源，不能混写。正常浏览器本轮仍生成全量 PDF，页数不作跨来源恒等承诺。

技术版式通过不等于业务格式认可（仍待确认），合成数据不等于真实 A03，模拟签署不等于正式电子签名认证。未知403保留普通读取失败，已加载报告仍会清空并禁印；无实时撤权推送、不承诺撤回已下载 PDF。历史登录失败逐例归因仍不确定，授权起点诊断头较晚保持非阻塞。本轮不启动 G/A03 或部署。
