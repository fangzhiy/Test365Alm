# AUTH-FIX-01 合入与 CI-TRIGGER-01

2026-11-11。用户确认独立审核通过后，核对 PR #11 仍为已审核 head
`c026b6cb4ab19b1de783418f3eb0de34eae49427`，无新增认证或业务代码；以精确 head 合并到 dev，
合入提交为 `3178e4a8fddd4d787550547f92ee805ae99c2e0b`。沿用现有 codex-main 开发目录及 dev 集成目录。

本轮唯一实现改动：`.github/workflows/ci.yml` 的 push branches 从 `['**']` 改为 `[dev, main]`。
逐字比较确认 pull_request 配置原样保留，permissions、六个 Job、命令、门禁、制品和清理逻辑均未变化。
codex-main 也包含此配置；其 push 不匹配 push 事件，但有开放 PR 时，新提交仍可触发
pull_request/synchronize，这是预期行为。本轮没有为验证制造空提交或测试分支。

实际验证：

- 根目录 `python -m unittest tools.tests.test_ci_workflow -v`：15 项，零失败/错误/跳过。
  清理负向用例的预期 FAIL 文本不代表 unittest 失败。
- 根目录 `git diff --check`：通过；标准库逐字比较：只有上述一行 workflow 差异。
- `trail check`：提交前通过，无冲突。
- 认证完整证据引用 c026b6c 的 Push CI [42945315754](https://github.com/fangzhiy/Test365Alm/actions/runs/42945315754)
  和 PR CI [42945777946](https://github.com/fangzhiy/Test365Alm/actions/runs/42945777946)，均六 Job 成功。
  对应 105 单元、219 集成、450 前端、212 Python、16 全集浏览器及独立 UI 1 项，均零失败/错误/跳过。
  不把这些旧证据称为本轮重跑。本轮不手动重跑 Docker/浏览器全集；集成推送正常触发的 CI 结果以对应
  Actions 运行和交付摘要为准，不提前宣称成功。

已知限制：只接受已复现的 SavedRequest 会话覆盖缺陷；历史登录失败逐例归因仍不确定。
授权起点诊断头设置较晚保留为非阻塞限制，本轮不修。保留原本地工具配置修改。
F 报告、G/A03 继续暂停，不部署、不升级依赖、不新增 worker 或工作区。
