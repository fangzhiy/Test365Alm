# R11-CORE-BATCH04-FIX01 执行记录

完整输入：[原文](../inputs/R11-CORE-BATCH04-FIX01.md)。本轮仅修正资产allocator/current单调完整性、感染complete冻结幂等及正式scanner登录身份provision。以下为实际过程，未完成的验收不得写PASS；保留原R11执行记录及红CI。

## 实际起点

2026-10-17重新fetch退出0，并通过GitHub接口核对PR10仍Open/unmerged，head为 `c150958d964b85370393373516e377465ceb7ce2`，base `feat/r10-m06-001@d213e47bebbe0f89522be48c3e58003d008cdf06`。PR9仍Open，默认分支 `chore/r01-status-001@a2419e292c004b2cd4c7278bdaa40b1ce3442a22`。本地同SHA、工作区干净；继续现有PR10，不新建PR11。

审核基准Push38949795012/PR38949800659六Job成功，实际PR checkout `1b6993441c442890b0a5ee85a5d58c2fa7e5b6aa`；63单元/143集成（M05 DB15+升级1+HTTP2，整个OIDC23）/213Vitest/106Python/14完整浏览器+独立项目UI1，均零F/E/S。这是旧基线，不代表本FIX已通过。

## 顺序与安全边界

1. 先新增真实runtime/合法MEMBER的allocator回退、跳号、旧current及感染同key重放回归，原实现运行RED。
2. 仅追加V21约束，不改V1—V20；测试故障探针移V22。修复持久化terminal error，同key先重新授权，不冻结transient503。
3. 正式repository provision逻辑与真实非空V20升级共用同一来源；数据库能力role与LOGIN/secret配置分开。仅操作本轮Testcontainers资源，不连接实际开发或生产库。
4. 精确门禁/原六Job、所有旧回归保留；最终代码提交验证后再交付文档，不循环追写自身SHA。

本机 `docker version` 当前退出1：29.7.2客户端存在，desktop-linux engine命名管道不存在。本机真实PG/S3/clamd为BLOCKED_LOCAL；使用已有UbuntuCI运行真实红/绿证明，不用mock或owner执行业务替代。历史38946999225登录/me401根因未证明，不改认证/超时/retry。

## 当前验收

仅新增回归的远端提交`c3c1d13bd471c5c677752bec9f4da5dc07956f27`已经实际push退出0，父提交为上述c150958；当时未修改任何生产文件。新增AssetDatabaseIT 1项（6类独立runtime攻击）及AssetTerminalOutcomeIT 5项。系统Maven `mvn -B -ntp -DskipTests test-compile`退出0仅证明编译，不作测试通过；真实[Push38953261084](https://github.com/fangzhiy/Test365Alm/actions/runs/38953261084)/[PR38953268643](https://github.com/fangzhiy/Test365Alm/actions/runs/38953268643)已触发。

Push server Job117149672709实际RED：63单元通过；149集成，3失败、0error、0skip，Failsafe退出1。`AssetDatabaseIT.publishedAssetAllocatorAndCurrentPointerCannotMoveBackwardOrGap`中allocator回退、任意+100、allocator+current一起退回rev1、无修订advance、真实CLEAN新修订跳号共5种均实际提交成功；单独pointer回退被旧guard正确拒绝。`AssetTerminalOutcomeIT`5项中2失败：同key/content首次422/FILE_INFECTED第二次409/UPLOAD_REJECTED；同key不同hash为400而非409。新key409、撤权/停用/过期拒绝、真实scanner暂时失败后原key恢复3项通过。失败发生在目标断言而非环境或权限夹具。确认真实RED后才开始V21和production terminal修复。

报告门禁自身新增精确名称断言：旧REQUIRED运行1项失败退出1；新增F01、5个terminal、2个provision确切名称后，7项离线门禁通过退出0。首次沙箱运行因Windows临时目录ACL失败，正常用户权限复跑通过，未削弱测试。探针/CI离线测试15项退出0：实际工具版本常量及历史查询改V21、失败文件改V22（这不等于真实启动验证）。

F01、F02、F03、F04、F05：NOT_RUN，实施验证中。当前没有宣称新迁移或terminal重放通过。

修复候选本机验证：系统Maven `mvn -B -ntp test`最终退出0，66单元（新增CoreCommandsOutcomeTest 3）、0F/E/S，编译47个测试源。此前沙箱回环被拒导致网络测试错误，停止该次进程退出1；正常权限第一次又遇到并行编写中的helper参数未补完编译失败，全部源码完成后复跑66通过。聚焦3单元曾因Spring两个422枚举别名直接比较失败，改为HTTP数值422并仍精确比较code/message，非业务语义放宽。

Windows Wrapper独立`.\mvnw.cmd -B -ntp test`退出1，`.m2` MinIO缓存AccessDenied、零测试，不能与系统Maven混算。前端`npm ci --no-audit --no-fund`沙箱EPERM退出1，正常权限复跑0；`npm run lint`退出0（9项既有warning）、完整JSON Vitest213/0F/E/S退出0、`npm run build`TypeScript/Vite退出0，`python tools/verify_r11_asset_web_report.py local-evidence/r11-fix01/vitest.json`退出0。完整`python -m unittest discover -s tools/tests -v`最终116项退出0（新provision9及FIX门禁1），`python tools/validate_package.py`退出0，仍明确184业务规划验收NOT_EXECUTED。

Push RED制品已读取到被忽略的`local-evidence/r11-fix01-red/backend/target/failsafe-reports`，XML汇总确认为149/3F/0E/0S；PR同样失败，实际checkout/build为`fec1dd0e53655ca0cc9dc823f4ff25a31a7c5de5`，不是分支head。原前端、工具、故障与浏览器五Job通过。新修复真实GREEN必须另查，不用这次RED的旧层通过代替整轮验收。

未合并PR9/10、未部署、未恢复定时任务、未修改默认分支/保护/可见性，未进入M12。
