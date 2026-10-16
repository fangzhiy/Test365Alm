# R11-CORE-BATCH04 执行记录

全文：[R11输入](../inputs/R11-CORE-BATCH04.md)。当前实施中，未宣称验收通过；内部自审不等于独立审核。

## 起点与边界

2026-10-17核对GitHub：默认`chore/r01-status-001@a2419e292c004b2cd4c7278bdaa40b1ce3442a22`，PR9仍Open/unmerged，head`d213e47bebbe0f89522be48c3e58003d008cdf06`与审核相同。未发现R11分支或后续用户提交；本地该head工作区干净。普通fetch失败`getaddrinfo() thread failed to start`，授权重试相同fetch退出0。新建`feat/r11-m05-001`，计划新PR base `feat/r10-m06-001`、Depends on PR #9；未合并PR9。

R10最终Push38937842395/PR38937893784六Job成功，PR实际checkout`1c82824b11111d11c9491e2540036f5fddf2b923`；46单元/125集成/181前端/95Python/13完整浏览器+独立1，作为保留基准。独立审核反馈的相同内容PATCH仍递增版本为后续公共API精修，不扩入本轮。

## 实施顺序和验证清单

原`tasks/plan.md`/P0任务不覆盖；本用户指定轮次记录维护本轮细化任务。

- [x] 真实GitHub/本地基准，完整原文归档，建立单层依赖分支。
- [x] 冻结[资产接口契约](../../../contracts/assets.md)及威胁边界：不可信文件/文件名/MIME、scope、S3/DB非2PC、scanner fail-closed。
- [ ] 受控隔离上传纵向切片：adapter/真实容器、规则RED→GREEN、session/raw PUT、无发布前下载。
- [ ] CLEAN发布/不可变版本/typed link/download及真实DB、HTTP、并发/故障/升级验证。
- [ ] 实际详情附件UI、确定性状态回归及真实MEMBER/VIEWER DOM流程。
- [ ] 精确门禁、六Job、完整回归、自审修复、代码提交CI。
- [ ] 文档交付、最终Push/PR CI、实际checkout/远端文件回读、独立审核停止点。

本轮不部署、不恢复定时任务、不改变默认分支/保护/可见性，不进行生产资源或客户数据操作；不开发M12/后续模块。N01—N10当前NOT_RUN，随实际证据更新。

## 实施中实际命令与自修记录

本机2026-10-17：Java17.0.2、Node26.0.0、Python3.12.8；目标CI Java21/Node24，编译target17未改变。Docker client29.7.2存在，但`docker version`访问`dockerDesktopLinuxEngine`管道不存在，本机真实PG/S3/clamd/Keycloak执行BLOCKED_LOCAL，不据此跳过CI。

| 命令/位置 | 退出码与实际结果 |
|---|---|
| 根目录 `python -m unittest tools.tests.test_r11_asset_evidence -v` | RED 1，新门禁模块尚未实现；不是业务验收 |
| 根目录 `python -m unittest tools.tests.test_r11_service_probe -v` | RED 1（缺模块），实现后0/3项；只验证离线探针逻辑 |
| 根目录 `python -m unittest discover -s tools/tests -v` | 沙箱临时目录权限失败1/104项；正常权限重跑1，仅旧浏览器正向fixture漏新M05名称，修正实际fixture后`-q`退出0/104项 |
| 根目录 `python tools/verify_r11_asset_web_report.py local-evidence/r11-frontend.json` | 0，真实Vitest210项，M05组件24+API5确切名称，无failure/error/skip/duplicate |
| 根目录 `python tools/validate_package.py` / `python tools/p0_health_check.py` | 均0；仍184产品AC未通过、P0 B01—B05 INPUTS_MISSING，不是业务验收 |
| apps/web `npm run test:run`、`npm run build`、`npm run lint` | 分别0/210项、0、0；原9项lint warning保留，无新warning |
| apps/server 系统`mvn -B -ntp test-compile` / `mvn -B -ntp test` | 分别0；完整单元60项，failure/error/skip均0；HTTP/容器IT仅可编译，不等于已执行 |
| apps/server Windows `.\mvnw.cmd -B -ntp test` | 1，用户缓存`io/minio`目录AccessDenied；未执行测试，不与系统Maven成功混记 |
| apps/web `npm ci` | 0，127 packages；随后Vitest沙箱临时缓存rename EPERM退出1/零测试，正常权限重跑0/210项；lint0、build0 |
| apps/web `npm audit --omit=dev --registry=https://registry.npmjs.org --json` | 0，production报告0漏洞；不是全量安全认证 |

前端已捕获并修复真实RED→GREEN：发布成功后读取失败无法再次刷新；并发资产rev2发布时读取本会话应精确使用已确认revisionId，不误选currentRevision。失权、项目切换、未知意图、延迟回包、取消/超时/StrictMode和blob URL清理保留回归。Playwright仅发现14个用例，实际执行尚待CI。

适配器初次Maven缓存写入AccessDenied与测试socket权限失败独立记录；不算业务RED。Windows安全软件阻止EICAR测试落盘，不绕过；本机检测单元用内存，Ubuntu真实EICAR上传/扫描IT保留。正常权限完整单元曾58项/1error：MinIO9复制前HEAD需要真实Content-Length/Last-Modified，已修正fixture并精确断言请求顺序，未改变生产复制实现。新增真实Tomcat上传读空闲测试2项后，全套60项通过；不删除断言。

安全自审发现普通runtime原可INSERT CLEAN扫描结果，已改为独立受限scanner DB identity，测试要求runtime直接伪造42501。真实扫描日志独立提交，业务发布回滚允许保留扫描事实但无可下载revision；已感染证明即便audit回滚也不能通过重扫反转。不是S3/DB 2PC。raw PUT新增15s可配置读空闲限制并用真实不完整HTTP流验证；它不是总墙钟预算。

本轮新增/修改文件按职责：`asset/`生产/测试、正式V20、2个OIDC HTTP矩阵、旧升级到latest计数与冻结V18→V19目标；附件面板/API/三处详情接线及新browser；私有Compose、配置生成/受限账号、V21失败探针、精确M05门禁/离线对抗测试、原6Job证据白名单；契约/ADR025/README/AGENTS/本输入与记录。发布前核对完整git清单，原V1—V19无改动。

## 首次真实CI与补强（历史保留）

首次代码候选`1fcbda1caae9a427470fd1156e53fcbf9b422cbf`经普通git正常推送，创建[PR10](https://github.com/fangzhiy/Test365Alm/pull/10)，base为`feat/r10-m06-001`、Depends on PR9。普通push同样遇到沙箱DNS错误，授权相同命令退出0；远端ref回读与本地相同，无Git Data API映射差异。

- [Push38945148129](https://github.com/fangzhiy/Test365Alm/actions/runs/38945148129)及[PR38945221859](https://github.com/fangzhiy/Test365Alm/actions/runs/38945221859)均failure：仅server失败，其余五Job成功。
- Push server117122280298：Surefire60成功；Failsafe143、failure2/error12/skip0。首个故障为`scannerUnavailableAndTimeoutRemainRetryableWithoutPublication`在真实扫描器恢复阶段超时180秒，随后共享scanner不可用；两个新HTTP方法503。非空V19升级先执行成功，其余旧模块保持通过。修复中，不据其他Job绿色判整轮通过。
- 两条browser完整14项及独立项目UI1项通过；真实S3匿名403、真实clamd benign CLEAN、MEMBER上传/发布/reload/download/rev2冻结rev1、独立VIEWER负向均实际执行，不是mock扫描。后续代码仍须最终CI回归。
- 本地独立自审补强：同一runtime连接在revoke/disabled/tenant expiry后直接读六表为0；V20升级后六新表为空；同键并发audit/Outbox各6、core幂等3。属于补强断言，不新增业务范围。
- Seaweed官方日志会输出AccessKey及identity前8字符：Python日志过滤新增精确ACCESS_KEY及该前缀；Testcontainers启动失败日志也在输出前脱敏，不输出add-mask原值。合成回归实际RED→GREEN；Python105通过，后端新增1项后系统Maven61通过。只保留故障摘要，不隐藏失败。
- 首轮PR实际checkout为`f4d0403f18d6e558e8e2457925fdee5b4cea4bc4`，并非分支head。恢复失败的确切内部clamd重启原因没有足够日志，不能编造为已证实端口变化。有限修复改为冻结/恢复本轮真实scanner容器，fresh inspect校验ID/标签/StartedAt/真实端口始终相同；恢复后实际benign扫描须CLEAN，不重启应用。另保留真实连接拒绝及受控TCP读超时对照，均503且无发布；不是mock扫描返回。未提高180秒方法超时，恢复预算由180缩为60秒。下一CI实际验证此修复。
