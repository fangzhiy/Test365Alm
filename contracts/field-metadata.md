# R08 M04 字段定义第一切片契约

状态：已实现最小切片；实际验证见 R08 执行记录，不是完整 M04 验收。项目路径 `/api/v1/projects/{projectId}/field-definitions`。

- GET：`{items,nextCursor}`；limit 默认 25，上限 100，cursor 为最后一条 UUID，按 UUID 升序 keyset；只列持久 CUSTOM 定义。
- GET `/system`：只读 SYSTEM 保留字段目录（应用既有字段，不伪称自定义扩展值已接入需求/用例）。
- POST：创建；PATCH `/{id}`：追加不可变定义修订；GET `/{id}`：当前修订；GET `/{id}/revisions/{version}`：指定修订。
- POST `/{id}/validate`：`{revision:正整数,value:string|null}`，明确对指定不可变定义修订验证，返回 `{valid:true,revision,value}`；错误为 422 VALIDATION_FAILED。

定义正文仅接受 `key,type,required,maxLength,precision,scale,minimum,maximum,options,defaultValue`。
key 为小写 `[a-z][a-z0-9_]{0,63}`，不得使用目录保留名。type 为 TEXT/NUMBER/ENUM；required 必须为 boolean。
TEXT 必需 maxLength 1..100000，长度按 Unicode code point；NUMBER 必需 precision 1..28、scale 0..precision，minimum/maximum 可选精确十进制字符串；ENUM 必需 1..100 个唯一非空字符串选项（每项至多 100 字符）。
NUMBER 值使用无指数精确十进制字符串，不接受 JSON 浮点数，避免浏览器精度丢失；检查 precision、scale、闭区间上下界。缺值先取 defaultValue，再检查 required；默认值必须满足同一验证。
可选字段的 defaultValue 可以显式为 null；required=true 时不能以 null 满足必填约束。页面留空表示未提供默认值。
与类型不相关的约束字段拒绝。key/type 创建后不可变；其他变化产生新修订，不修改旧定义或重新解释已存历史。本轮不存储业务实体扩展值，不实现数据回填。
返回 `{id,projectId,kind:"CUSTOM",rowVersion,definition}`，ETag 为带引号正整数。
写定义仅实际有效 PROJECT_ADMIN；MEMBER/VIEWER 可读和验证。SYSTEM 不提供写路径。
所有写请求同源会话+CSRF、8..128 字符 Idempotency-Key；PATCH 必须单个强正整数 If-Match。缺失/通配 428、格式错误400、陈旧412 STALE_VERSION，同键异请求409 IDEMPOTENCY_KEY_REUSED。
幂等按租户/项目/主体/路由/key，24小时；重放冻结快照并重新授权。业务/不可变修订/审计/Outbox/幂等同事务。
通用错误体沿用 `{code,message,requestId}`，401/403/404 保持已有语义；任何权限检查先于资源/版本信息返回。
