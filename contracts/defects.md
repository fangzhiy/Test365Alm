# M10 缺陷第一核心闭环（R09）

这是有限切片契约，不是完整 M10/M05 自定义工作流。复用真实 OIDC 会话、动态项目权限、CSRF 与 runtime RLS。路径根 `/api/v1/projects/{projectId}/defects`。

## 输入与响应

- POST 根：`{title,description,severity,priority,requirementId?}`，201；OPEN。title纯文本1..500（trim后非空），description0..100000；severity LOW/MEDIUM/HIGH/CRITICAL，priority同枚举。未知字段/错误类型400，空或超长422。
- POST `/from-step`：上述四个内容字段加 `{runId,attemptId,stepKey}`，不接受派生test/revision/实际结果/作者。仅已完成尝试中的 FAIL 或 BLOCKED 步骤；尚在执行409 SOURCE_NOT_FINISHED，PASS/NOT_RUN 409 NOT_FAILED_SOURCE。冻结源快照与正确链路，BLOCKED 与 FAIL 明确区分。服务端取相同用例修订的有效需求追踪（最多100，超过拒绝），冻结真实需求修订；不存在追踪不虚构需求。
- GET 根：`?q=&cursor=&limit=25`，title包含查询（q至多200），UUID升序keyset，limit1..100；`{items,nextCursor}`，不得自动下载全部页。
- GET `/{id}` 当前详情，真实强ETag；PATCH `/{id}` 仅内容四字段全快照，追加修订，不改来源关系/状态。GET `/{id}/revisions` 按revisionNo升序分页；GET `/{id}/revisions/{revisionId}` 不可变指定快照。
- POST `/{id}/transitions`：`{status,reason}`，追加状态修订。RESOLVED需要非空修复说明；普通缺陷有该说明即可从RESOLVED关闭。来源缺陷还必须通过下述复测条件。CLOSED不能编辑或流转，不提供历史改写入口。
- POST `/{id}/retests`：`{}`，仅RESOLVED的来源缺陷，复用M09原run合法rerun，记录此次resolved修订与新attempt；返回 `{runId,attemptId}`。受M09独立写权限约束，不另建执行引擎。
- GET `/{id}/verification`：`{eligible,runId,attemptId,reason}`，只认本缺陷当前RESOLVED周期明确创建的最近复测，原run/manifest/step正确，FINISHED且整个attempt与绑定step均PASS，没有该run活动尝试；FAIL/活动/无关PASS不满足。关闭时服务端及数据库重新检查，客户端verified字段拒绝。
- GET `/{id}/retests`：有界分页，显示当前及以前周期复测摘要；具体原FAIL/PASS步骤通过既有attempt详情接口获取。关闭后原来源、复测和历史仍可读。

详情 `{id,projectId,displayNumber,rowVersion,createdBy,createdAt,currentRevision,links}`；显示 BUG-{displayNumber}。修订 `{id,defectId,revisionNo,title,description,severity,priority,status,reason,createdBy,createdAt}`。links是只读的强类型 RUN_STEP/REQUIREMENT 行，含真实引用和来源冻结actual/expected/conclusion/version，不是任意JSON引用。稳定UUID、显示编号、修订UUID分离。

状态机：OPEN→IN_PROGRESS/RESOLVED；IN_PROGRESS→RESOLVED；RESOLVED→CLOSED/REOPENED；REOPENED→IN_PROGRESS/RESOLVED。非法409 INVALID_TRANSITION。重新RESOLVED形成新验证周期，不复用旧周期PASS；不自动关闭、不删除失败历史。

REOPENED/OPEN/IN_PROGRESS 的 verification 必须为 false；保留旧 resolved 修订仅供历史，不表示当前可关闭。CLOSED 仍可查看关闭时关联的历史，后续 M09 新尝试可能使实时 verification 不再满足，不能据此改写已保存关闭修订。

## 权限、版本和事务

ADMIN/MEMBER：defect:read/create/update/transition/history:read；VIEWER仅read/history。未加入项目的TENANT_ADMIN无权；撤权、停用、租户成员失效对下一直接请求生效。

所有写操作需要CSRF和8..128字符Idempotency-Key。PATCH/transition/retest要求单个强正整数If-Match（缺失/通配428，畸形/裸数/弱标签/溢出400，旧版本412 STALE_VERSION）。幂等作用域 tenant/project/principal/route/key，24小时；相同键与原始请求/原版本重放冻结响应，异内容409 IDEMPOTENCY_KEY_REUSED，重放先重新授权；过期键按既有新意图规则。编号受事务分配器行锁保护，不能MAX+1。

defect/revision/link/retest/audit/Outbox/幂等同事务，失败全部回滚。runtime不可UPDATE/DELETE/TRUNCATE历史。非法链路先授权再404，不泄露内容/版本。源码请求只用参数化SQL。未知写结果页面锁定原快照/key/tag重试；412保留草稿，只有明确放弃/采用最新后建立新基础版本；写成功GET失败只重试GET。项目/主体/权限变化取消并使旧轮次失效。

不含附件、通知、SLA、复杂树/批量、同步、AI、完整报表；M04自定义值与M05工作流入口尚不存在，不伪称依赖已完成。
