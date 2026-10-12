# R08 M11 需求→用例追踪第一切片契约

状态：已实现最小切片；实际验证见 R08 执行记录。仅有向 REQUIREMENT_TEST，不是完整实体注册/影响传播模块。
项目路径 `/api/v1/projects/{projectId}/trace-links`。

- POST `{requirementId,testCaseId}`：创建 ACTIVE 关系，201；两个真实端点必须同租户/项目、未删除且可读；冻结当前需求修订/用例修订。
- GET `?requirementId=<uuid>&cursor=<uuid>&limit=25`：需求详情下稳定 UUID keyset `{items,nextCursor}`，limit 1..100；所有状态均可读。
- POST `/{id}/invalidate` `{}`：If-Match + Idempotency-Key，追加失效修订，返回新版本；不物理删除审计历史。
- GET `/api/v1/projects/{projectId}/coverage?requirementId=<uuid>`：同步数据库快照的有限单需求汇总。

覆盖响应为 `{requirementId,linkedTests,activeTests,passedTests,rule}`；rule 为 `CURRENT_BOUND_REVISIONS_LATEST_FINISHED_NO_ACTIVE`。页面明确区分关联分母、活动执行和已执行通过，刷新覆盖仅发 GET。

关系返回 `{id,projectId,requirementId,testCaseId,requirementRevisionId,testRevisionId,rowVersion,state}`，state ACTIVE/INACTIVE；同范围同端点最多一条 ACTIVE。
创建/失效仅有效 PROJECT_ADMIN/PROJECT_MEMBER；VIEWER 可读。CSRF、强 ETag、幂等和错误体沿用字段契约及现有业务规范。
同键同请求返回冻结结果；同键异请求409；不同键并发相同有效端点409 DUPLICATE_LINK，不生成第二关系。撤权后原键不可重放敏感内容。
关系、追加修订、审计、Outbox 和幂等同事务；端点复合 FK，不接受任意对象字符串。

覆盖口径：分母为当前需求的 ACTIVE 唯一用例关系数量；linkedTests 仅表示关联，不代表验证。
通过覆盖仅在关系绑定需求修订仍等于当前需求修订、绑定用例修订等于当前用例修订，且该用例对应的最新已完成运行尝试（finished_at DESC、attempt id DESC 确定排序）使用该确切用例修订并为 PASS 时计数。
存在活动尝试时单独 activeTests 计数；不得把上一 PASS 当当前活动尝试通过。没有执行为 0；更新需求/用例后旧绑定不得增加当前 PASS。
同步查询不是异步 coverage_projection，不承诺 watermark、suspect 确认、完整四类覆盖或 M10 缺陷追踪。
