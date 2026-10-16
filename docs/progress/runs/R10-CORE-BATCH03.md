# R10-CORE-BATCH03 连续实施记录

输入全文：[R10](../inputs/R10-CORE-BATCH03.md)。范围：主线事实纠偏、M06发布/周期/typed范围/冻结质量快照第一切片。内部实施和自审不代表外部审核。本文持续记录红绿结果，最终交付SHA和最新CI在执行回复核对，不循环提交文档自身SHA。

## 阶段 A / 实际起点

2026-10-17 本地起始 `feat/r09-m10-001@32e13d86731b0e2bc5a7d844c94a6f471f9a941e`，工作区干净。GitHub实际默认 `chore/r01-status-001@a2419e292c004b2cd4c7278bdaa40b1ce3442a22`。PR7/8已merged，merge分别 `e908b8e9f23be65efa95ecdf358229406d9f0fe4`、`a2419e292c004b2cd4c7278bdaa40b1ce3442a22`；无后续主线提交或R10分支。首次普通fetch失败 `getaddrinfo() thread failed to start`，获准正常网络执行 `git fetch origin --prune` 退出0，实际更新远端跟踪引用。没有回退/强推。新分支 `feat/r10-m06-001` 从真实主线建立。

读取AGENTS、README、当前状态、R09全文/记录、M03/M04/M06/M07/M08/M09/M10/M11手册、相关契约、ADR和现有授权/业务/数据库/前端/门禁。原始输入从中转保存的完整原文归档，非摘要。

[主线38931708362](https://github.com/fangzhiy/Test365Alm/actions/runs/38931708362)为push，head/实际checkout都为`a2419e2…`。server117077060000、browser117077060441、web117077060473、migration117077060552、planning117077060630、readiness117077060692均success。tree `1478f5c3c43399ceeacaad1fb8d46bdd1ae5c87e`与R09交付相同。六制品均存在未过期。

数量：Surefire44、Failsafe110（M07 DB13/升级1；M09 DB23/升级3；M04/M11 DB10；M10 DB9/升级1；OIDC全类19中M10方法2）、Vitest160、Python85、完整Playwright12及独立项目UI1，失败/错误/跳过0。旧精确M07/M09/M11/M10门禁实际执行success。真实同PID停库live200/ready503→恢复200，回环`::ffff:127.0.0.1`，正式V18成功/专属V19失败识别/安全清理均通过。

本机smoke均对应未改业务代码的a2419e2：

| 实际命令 | 退出/数量/环境 |
| --- | --- |
| `mvn -B -ntp test`（系统Maven） | 首次沙箱Java attach管道拒绝，中断exit1；获准重跑exit0，44/0/0/0。不是Wrapper结果 |
| `npm run test:run` | 首次TEMP rename EPERM退出1/0测试；正常权限重跑exit0，160/14文件 |
| `npm run build` | 0，TypeScript与Vite通过 |
| `python -m unittest discover -s tools/tests -v` | 首次TEMP权限错误exit1（85测试/90fixture错误）；正常权限重跑exit0，85通过 |
| `python tools/validate_package.py` | 0；32模块/192WBS/160模块AC/24E2E完整，产品仍NOT_EXECUTED |
| `docker version` | 1，client29.7.2/desktop-linux；engine命名管道不存在，BLOCKED_LOCAL |

本机Java17.0.2、系统Maven3.9.14、Node26.0.0/npm11.12.1、Python3.12.8；主线CI Java21.0.12.1、Node24.21.0/npm11.19.0、Python3.12.15。编译目标17不改。阶段A无阻断，直接进入B；development-status/README/AGENTS顶部纠正主线已合并事实，R08/R09历史不删除。

## 阶段 B（实施中，未预先验收）

先冻结 [Release契约](../../../contracts/releases.md)。正式新版本计划V19，旧V1—V18不改，失败探针顺延V20。仅ADMIN写，MEMBER/VIEWER读；范围冻结需求和用例修订；质量指标保留原始分子分母、版本与证据，历史不重算。

M02—M10当前NOT_RUN，等待实际代码/数据库/HTTP/页面/CI证据。用户未授权的部署、自动合并、定时任务、后续模块均未执行。
