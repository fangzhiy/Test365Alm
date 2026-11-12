# CI-PREP-01

2026-11-12，基线 `fed6024a47dece430ceed3ece90592aea4feb5cb`，继续 [PR #13](https://github.com/fangzhiy/Test365Alm/pull/13)。仅修改浏览器 CI 准备、配套工具/测试和说明；已接受的网格、恢复、认证、签署及历史语义不变。main 保持 `b8975e4604295269c2c0e8efef461614fc4b37da`，真实 G/A03 暂停，合成演示不代替正式验收，业务报告格式认可仍待确认。

| 问题编号 | 原失败证据 | 改动及相邻检查 | 修复后证据 | 被测 SHA | 自验结果与限制 |
|---|---|---|---|---|---|
| CI-PREP-01-A | [dev 43057338879](https://github.com/fangzhiy/Test365Alm/actions/runs/43057338879)、[PR 43057350036](https://github.com/fangzhiy/Test365Alm/actions/runs/43057350036) 的 attempt 1/2 均在字体 apt 安装 DNS 失败、退出100；浏览器 NOT_RUN，只有 checkout 制品，无 XML/OQ/PDF。旧日志及制品保留。 | 仅浏览器 Job 固定 Ubuntu24.04；官方 Noto 固定提交下载，Regular/Bold/OFL 全部 SHA256 通过才安装。核验实际家族、字重、文件及中文覆盖；保留锁定 Playwright 的 `--with-deps`，增加正式 dry-run 依赖复核及真实 Chromium/CDP 字体与中文 PDF 预检。准备诊断 always 上传。 | 本地 Ubuntu24.04 受控容器中下载、匹配、系统依赖安装和复核、Chromium153.0.8010.12 启动与中文 PDF 均通过；工具229通过。实现提交两路正常 CI（43145069688/43145075758）attempt1 六 Job 全成功；原始计数与同来源 OQ/FIX03/PDF 已核对，详见下文。文档收尾最终 head 的 CI 另附 PR。 | 实现被测 SHA `633732e77f6965b436b1b35c5e7c4882afc12c27`；PR checkout `00ada51c4037ee80c0e79c11bdbd3ef6b301b776`。本地提交前同源；文档收尾最终版本另记 PR。 | 实现版本完整 CI 自验通过；文档收尾最终 head 的实际状态以 PR 最终交付为准。固定 runner 不等于证明 DNS 已修复；下载/安装/校验失败仍阻塞，不生成空报告或改门禁。 |

## 来源、验证及边界

- Noto 官方仓库 `notofonts/noto-cjk`，tag `Sans2.004` 对应不可变 commit `523d033d6cb47f4a80c58a35753646f5c3608a78`；`Sans/OTF/SimplifiedChinese/NotoSansCJKsc-{Regular,Bold}.otf`，SIL OFL1.1。SHA256（Regular/Bold/根 LICENSE）：`2c76254f6fc379fddfce0a7e84fb5385bb135d3e399294f6eeb6680d0365b74b` / `b5f0d1a190a7f9b43c310a8850630af12553df32c4c050543f9059732d9b4c0a` / `6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2`。没有缓存或备用下载源，TLS 不关闭；许可证、来源和实际字体检查进入制品。
- Playwright 保持1.63.0，其锁定代码支持 Ubuntu24.04 和26.04；不将原故障归因于26.04不受支持。系统库仍通过其正式 `install --with-deps chromium` 安装，之后 `install-deps --dry-run chromium` 必须成功；真实默认 headless Chromium 启动和 CDP 实际 Regular/Bold 字体核验必须成功。
- `python -m unittest tools.tests.test_ci_fonts tools.tests.test_ci_workflow -v`：24通过。`python -m unittest discover -s tools/tests -v`：229通过。新增失败分支覆盖下载/哈希错误不安装、字体工具缺失、错误家族/字重/中文覆盖、实际匹配回退拒绝，以及失败保留 BLOCKED/NOT_RUN。
- 本地准备链使用一次性 `mcr.microsoft.com/playwright:v1.63.0-noble@sha256:eff16c30e6f3f4af0a03fa4b706120d5e9b0891c344a27d64559aff5900a4a27`，应用依赖仍用仓库 lockfile 执行 npm ci。记录在 `local-evidence/ci-prep-local/`，最终日志 `local-evidence/ci-prep-local-final.log`。首轮临时 shell 的 CRLF 曾使输出参数带 `\r`，修正该本地夹具为 LF 后，PDF/PNG/JSON 保存及中文粗体/正文渲染检查通过；没有把该夹具问题当原缺陷 RED。容器 --rm，持久演示项目和检查点未操作。
- `git diff --check`、Trail build/check 执行结果见本地日志及 PR。Trail 的 l-494264d8 仍对比较 main 范围内已接受的网格 reset 词法命中；本批未修改运行读取锁或任何网格源码，既有回归保留。该继承冲突不通过删 learning 或放宽门禁掩盖。
- 六 Job、push(dev/main)/pull_request、permissions、单 worker/零 retry、独立 UI/完整浏览器、报告门禁、资源归属与清理原样保留。旧 head 不再重复运行；本地准备成功不能替代新 head 的完整 CI。最终 CI 字体变化后的业务样张需按其实际页数及来源检查，不凑旧页数，不复用旧 PDF 冒充。

## 适用自查

| 项目 | 状态 | 依据 |
|---|---|---|
| 原问题对应 | 已验证 | 保留原 DNS/apt100 RED；字体获取改为固定官方内容校验，正常链实际运行。 |
| 相邻路径 | 已验证 | 正式系统依赖安装、dry-run、默认 Chromium 启动及 CDP/PDF。 |
| 失败语义 | 已验证 | 工具反例；脚本返回非零并保留 BLOCKED/NOT_RUN，缺报告仍失败。 |
| 正常完整业务路径 | 已验证 | 两路实现提交完整浏览器18/18、独立UI1/1，OQ/FIX03原始证据齐全；文档收尾最终CI另核。 |
| 来源一致 | 已验证 | 实现633732e及PR merge00ada51c父提交/树已核对；同一制品的OQ/index/字体诊断/PDF，最终文档提交另记PR。 |
| 变更边界 | 已有覆盖并核对差异 | 无业务源码/依赖升级；原门禁与清理保留。 |


## 实现提交的完整 CI 与样张

- dev push [43145069688](https://github.com/fangzhiy/Test365Alm/actions/runs/43145069688)、PR [43145075758](https://github.com/fangzhiy/Test365Alm/actions/runs/43145075758)：attempt1，六 Job 均成功，无复跑。实际 head 为633732e，push checkout=head；PR checkout为00ada51c，parents为main b8975e4及head633732e，tree与head一致。原始报告均为Java21单元107、完整集成226，前端521、Python229、完整浏览器18、独立项目UI1，零失败/错误/跳过；报告门禁成功。
- Ubuntu24.04字体和系统准备、dry-run、Chromium153.0.8010.12均通过；CDP实际字体为NotoSansCJKsc-Regular/Bold。PR浏览器原制品artifact13911895674，dev artifact及完整来源清单保存在`local-evidence/ci-prep/{run}/verification.json`，原ZIP、解包及逐页渲染同目录保留。未用旧head或本地七条替代。
- FIX03真实保存/重读的旧检查点三个标记false、proofReads=1、actions/backfills=0、writes=[]，checkpoint及完整前后快照不变；单周期及导航回归保留。标准场景14实例、16Attempt、29模拟签署，原始12PASS/2FAIL，独立两次PASS复测均未签署，CLOSED/REOPENED各一项；历史附件固定v1。故障数据仍为隔离项目。
- 逐页渲染并检查PR原始业务PDF：OQ普通运行/原FAIL/复测/汇总/偏差为5/6/6/6/6页；F长文本运行/汇总/偏差/普通运行为12/11/8/3页。中文、字重、长文本跨页、表头重复及末页内容正常；原有分页留白、尾注单页保持为已知限制，不改布局凑旧页数。样张明确合成数据，技术检查不是业务格式认可。
- 两路准备失败的原始历史记录保持不变；新实现没有删除字体或系统安装、放松超时/retry、忽略错误或补空报告。原归属清理及流程退出成功；本地一次性容器已移除，持久演示/旧项目/检查点未操作。
- 此记录收尾仅追加文档；其最终提交、正常触发的新CI及实际checkout/原始制品再在PR核对，不能将上述实现提交结果冒称为后续提交的运行。
