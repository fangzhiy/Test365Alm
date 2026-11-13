# UI-REFRESH-01（第一阶段）

基线 `433a48f`；实现 `aacad246adcfcba0aebbb0a7eb58fc5444ea69fa`；[PR #14](https://github.com/fangzhiy/Test365Alm/pull/14) 指向 main，待独立审核。采用 A 版配色与布局，新增 antd 5.29.3、@ant-design/icons 5.6.1；中文 ConfigProvider、分组导航/hash 恢复、项目概览、实验室网格及运行步骤/关联事实区已接真实接口。未改后端、契约、迁移、业务规则或报告打印样式；设计稿未入库。

| 自查 | 结果与证据 |
|---|---|
| 入口完整 | 已验证：9 个导航覆盖原功能；复核/签署、对象历史及报告保留原对象入口。模块切换卸载页面，hash 刷新恢复。 |
| 权限一致 | 已验证：真实 r03-viewer 实验室无创建/启动/分配操作；原浏览器 VIEWER 读取、打印及写拒绝断言保留。 |
| 迟到与失权 | 已有覆盖并新增验证：原宿主清理、gridVerified、历史锁回归保留；新增概览/关联事实切换、取消、迟到与拒绝测试。 |
| 数据真实 | 已验证：分页汇总，实例最新分类与历史 Attempt 分开；超限拒绝部分统计。演示为14用例/14实例/16Attempt，原 FAIL 保留；无项目动态接口，显示暂无。 |
| 打印不变 | 已验证：同一 OQD-01 前后均5页，除生成时间外提取文本相同；逐页检查中文、附件、签署及末页，无侧/顶栏。 |
| 测试真实 | 已验证：原521项全部保留，新增12项，共533项通过；无删断言、skip、retry、固定等待或放宽超时。 |

本地被测实现 aacad24：`npm run lint`、`npm run test:run`、`npm run build` 通过；`python tools/local_browser_runner.py --source aacad246adcfcba0aebbb0a7eb58fc5444ea69fa --with-assets --scenario oq-demo-01` 7/7通过，单worker/零retry，真实附件设施和归属清理成功。证据：`local-evidence/local-browser/f3542aee3b0240dda3d29da488bc68f8/`。最终正常 dev/PR CI 的 checkout、原始计数与制品另附同一 PR 交付记录，不将本地7条冒充CI全集。

截图及 PDF：`local-evidence/ui-refresh-01/` 的 `overview.png`、`laboratory-viewport.png`、`run-details-viewport.png`、`viewer-laboratory.png`、`run-before.pdf`/`run-after.pdf`、`pdf-comparison.json`。均来自既有 SYNTHETIC 演示，只读查看，未 resume/重建数据。JS 452.91→881.84 kB（gzip131.28→267.64）；CSS18.91→28.94 kB，保留大块构建提示。

差异/限制：运行详情仍嵌入实验室；其他业务页保留原样；关联事实显式读取；概览跨接口并非事务快照。原分页留白、业务格式待认可、真实G/A03暂停保持。首轮285ddd1浏览器6/7（首项登录前超时，原因未证明）与一次Vitest原生OOM留证据；限制Vitest为2 worker后全套通过。`git diff --check`、Trail build完成；Trail check四项关键词命中已逐处确认并在提交/PR说明：没有恢复固定演示数量、改变读锁/gridVerified/步骤版本。用户工具配置、旧数据和证据保留。
