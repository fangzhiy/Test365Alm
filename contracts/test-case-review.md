# 用例复核（D1/D2）

依赖总管批准的业务字段基线 `23d138cda8fd6d34ec598064778b42b121175109`。D 正式迁移 V25；V24 属于 C，故意失败探针由总管统一至 V26。旧迁移不改，迁移数按实际文件数计算。

复核人来自确切 `test_revision.business_fields.reviewer.value` 的稳定本地主体 UUID。提交人须为有效项目 writer；批准和拒绝还必须是被指定的复核人，项目管理员和租户角色不绕过。每次动作及持久幂等重放都重新检查当前项目读取/写入权限、RLS 和指定复核人的有效 writer 资格。不增加禁止自复核规则。

每个封存修订独立从 DRAFT 开始：DRAFT/REJECTED → SUBMIT → SUBMITTED → APPROVE/REJECT → APPROVED/REJECTED。拒绝必须填写理由（1..2000字符）；其他动作可填写理由。APPROVED 是该修订终态。编辑继续追加不可变内容修订，新修订从 DRAFT 开始；历史批准和原内容永久保留。动作仅作用当前修订；历史可读，不可重新决定。

GET/POST `/api/v1/projects/{projectId}/tests/{testCaseId}/revisions/{revisionId}/review`。POST 只接受 `{action,reason?}`，身份、摘要、状态、时间均由服务端导出；强 `If-Match` 使用用例共享 rowVersion，动作递增一次，与内容编辑相互排斥。POST 要求持久 `Idempotency-Key`；同意图返回冻结响应，键复用冲突；未知网络结果必须保留原键/原版本/原命令重试。审计、Outbox、复核事件、版本与幂等响应同事务。

GET 返回所选修订、当前修订、用例版本、服务端能力、最新状态及复核历史页。每个不可变事件绑定修订ID、revisionNo、指定人、执行人、时间、理由与 SHA-256 内容摘要；摘要由数据库从封存标题/描述/前置条件/完整业务字段/有序步骤及修订身份生成。历史分页按事件版本倒序，默认25、最多100；游标由客户端原样续传。viewer 只读；无范围读取权先拒绝，不泄露对象或复核记录。

验证尚在实施。离线单元、真实 PostgreSQL/HTTP、真实 Keycloak UI、完整组合验证分别报告；D1/D2 在真实验证及总管验收前不勾选。真实数据库和浏览器须先同步已验收 C 基线并取得串行资源时段，不触碰日常配置、W1 或5173/8080。
