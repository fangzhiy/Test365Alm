# CI-PREP-01

2026-11-12，基线 `fed6024a47dece430ceed3ece90592aea4feb5cb`，继续 [PR #13](https://github.com/fangzhiy/Test365Alm/pull/13)。仅修改浏览器 CI 准备、配套工具/测试和说明；已接受的网格、恢复、认证、签署及历史语义不变。main 保持 `b8975e4604295269c2c0e8efef461614fc4b37da`，真实 G/A03 暂停，合成演示不代替正式验收，业务报告格式认可仍待确认。

| 问题编号 | 原失败证据 | 改动及相邻检查 | 修复后证据 | 被测 SHA | 自验结果与限制 |
|---|---|---|---|---|---|
| CI-PREP-01-A | [dev 43057338879](https://github.com/fangzhiy/Test365Alm/actions/runs/43057338879)、[PR 43057350036](https://github.com/fangzhiy/Test365Alm/actions/runs/43057350036) 的 attempt 1/2 均在字体 apt 安装 DNS 失败、退出100；浏览器 NOT_RUN，只有 checkout 制品，无 XML/OQ/PDF。旧日志及制品保留。 | 仅浏览器 Job 固定 Ubuntu24.04；官方 Noto 固定提交下载，Regular/Bold/OFL 全部 SHA256 通过才安装。核验实际家族、字重、文件及中文覆盖；保留锁定 Playwright 的 `--with-deps`，增加正式 dry-run 依赖复核及真实 Chromium/CDP 字体与中文 PDF 预检。准备诊断 always 上传。 | 本地 Ubuntu24.04 受控容器中下载、匹配、系统依赖安装和复核、Chromium153.0.8010.12 启动与中文 PDF 均通过；工具229通过。最终两路 CI、checkout、原始计数、OQ/FIX03 与同来源 PDF 核对结果集中附于 PR。 | 本地为本提交的相同准备源码（提交前）；最终 head/PR merge-ref 以 PR 原始 CI 制品清单为准，不用基线结果代替。 | 本地准备链自验通过；完整 CI 的实际状态以 PR 最终交付为准。固定 runner 不等于证明 DNS 已修复；下载/安装/校验失败仍阻塞，不生成空报告或改门禁。 |

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
| 原问题对应 | 已验证（本地）；完整 CI 见 PR | 保留原 DNS/apt100 RED；字体获取改为固定官方内容校验，正常链实际运行。 |
| 相邻路径 | 已验证（本地）；完整 CI 见 PR | 正式系统依赖安装、dry-run、默认 Chromium 启动及 CDP/PDF。 |
| 失败语义 | 已验证 | 工具反例；脚本返回非零并保留 BLOCKED/NOT_RUN，缺报告仍失败。 |
| 正常完整业务路径 | 未完成，待新 head CI 原始结果核对后在 PR 更新 | 不以本地准备 PDF 或旧七条流程替代完整套件。 |
| 来源一致 | 本地已验证；最终 CI 见 PR | 原始 checkout/OQ/index/字体诊断/PDF 同一浏览器制品；最终 head 与 merge-ref 分别核对。 |
| 变更边界 | 已有覆盖并核对差异 | 无业务源码/依赖升级；原门禁与清理保留。 |
