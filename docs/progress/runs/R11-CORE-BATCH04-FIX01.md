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

F01、F02、F03、F04、F05：NOT_RUN，实施验证中。当前没有宣称新迁移或terminal重放通过。

未合并PR9/10、未部署、未恢复定时任务、未修改默认分支/保护/可见性，未进入M12。
