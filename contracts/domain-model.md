# 领域数据模型实施约束

状态：候选模型，尚未执行建表。这里给出逻辑字段与约束，P0/P1须据真实范围转换为Flyway迁移和集成测试；不是可以直接部署的完整DDL。

## 统一约定

项目域表的稳定键建议用UUID，并保留 tenant_id、project_id；唯一约束和外键必须把隔离范围一起纳入。物理结构可将通用资产身份放入 entity_registry，而不同领域内容采用明确子表；选择后须以ADR冻结，不能让通用JSON吞掉关键关系约束。

下面列出的业务字段是最低设计起点。除不可变事件/修订等特殊表，补充 created_at、created_by、row_version；可变状态的变更须记录审计。历史作者引用principal而不是当前显示名。

## 身份与授权

| 对象 | 核心字段 | 必须实现的约束 |
|---|---|---|
| `tenant` | `id, code, status, row_version` | code唯一；停用不删除历史身份 |
| `domain` | `tenant_id, id, name, status` | 域内项目不得跨tenant挂接 |
| `project` | `tenant_id, domain_id, id, code, state, schema_version` | (tenant_id,id)唯一；归档不物理删除 |
| `principal` | `id, issuer, subject, display_name, disabled_at` | (issuer,subject)唯一；历史作者引用稳定身份 |
| `project_member` | `tenant_id, project_id, principal_id, roles, valid_until` | 复合外键；撤权后清缓存并拒绝新访问 |
| `permission_policy` | `tenant_id, project_id, id, revision, action, condition` | 拒绝优先；导出、搜索、附件均走同一判定 |

## 元数据与发布

| 对象 | 核心字段 | 必须实现的约束 |
|---|---|---|
| `field_definition` | `tenant_id, project_id, id, entity_type, key, data_type, required, revision` | 同实体key唯一；类型变更需迁移计划 |
| `list_item` | `tenant_id, project_id, list_id, id, code, label, retired_at` | 停用值在历史中可解析 |
| `saved_filter` | `tenant_id, project_id, id, owner_id, query_ast, visibility` | 不存拼接SQL；共享受权限校验 |
| `release` | `tenant_id, project_id, id, name, start_date, end_date, scope_revision` | 开始不晚于结束；日期不带时区 |
| `cycle` | `tenant_id, project_id, release_id, id, name, start_date, end_date` | 跨release调整需要审计 |
| `milestone` | `tenant_id, project_id, id, release_id, target_rule_revision, due_at` | 目标规则版本固定；缺数据不自动达标 |

## 需求与测试设计

| 对象 | 核心字段 | 必须实现的约束 |
|---|---|---|
| `requirement` | `tenant_id, project_id, id, parent_id, current_revision_id, deleted_at` | 父树禁止环；稳定身份永不复用 |
| `requirement_revision` | `tenant_id, project_id, requirement_id, id, revision_no, title, body, attributes, created_at, created_by` | (requirement_id,revision_no)唯一；修订不可原地改 |
| `test_case` | `tenant_id, project_id, id, folder_id, current_revision_id, test_type` | 类型变化校验已有实例与资产 |
| `test_revision` | `tenant_id, project_id, test_id, id, revision_no, title, parameters, resource_refs` | 固定参数模式和资源修订 |
| `test_step` | `tenant_id, project_id, revision_id, step_key, ordinal, action, expected` | (revision_id,step_key)唯一；排序不改变稳定step_key |
| `test_configuration` | `tenant_id, project_id, id, test_id, config_revision, parameters, environment_selector` | 参数值与模式一致；变更产生配置修订 |
| `test_set` | `tenant_id, project_id, id, cycle_id, pinned_baseline_id` | 固定基线时差异按批准策略处理 |
| `test_instance` | `tenant_id, project_id, id, set_id, test_id, configuration_id, revision_selector` | 测试定义与实例分开；manifest在运行创建时解析 |

## 执行、缺陷与追踪

| 对象 | 核心字段 | 必须实现的约束 |
|---|---|---|
| `run` | `tenant_id, project_id, id, instance_id, manifest_id, state, initiator, row_version` | 状态转换CAS；历史manifest固定 |
| `run_manifest` | `tenant_id, project_id, id, canonical_json, sha256, created_at` | 含所有实际修订/资源/规则；不能依赖动态latest |
| `run_attempt` | `tenant_id, project_id, id, run_id, attempt_no, state, lease_epoch, agent_id, lease_until` | 同run的attempt_no唯一；旧尝试不可覆写 |
| `run_step` | `tenant_id, project_id, attempt_id, step_key, state, actual, evidence_refs, row_version` | 绑定manifest步骤；证据封存后只追加纠正事件 |
| `result_chunk` | `tenant_id, project_id, attempt_id, chunk_id, sha256, payload_ref` | (attempt_id,chunk_id)唯一；重复同哈希幂等，不同哈希冲突 |
| `defect` | `tenant_id, project_id, id, status, severity, assignee_id, current_revision_id` | 状态规则服务端执行；历史用事件或修订保留 |
| `defect_transition` | `tenant_id, project_id, id, defect_id, from_status, to_status, reason, actor, at` | 与缺陷状态、审计同事务 |
| `trace_link` | `tenant_id, project_id, id, from_entity_id, to_entity_id, relation_type, current_revision_id` | 端点同项目；跨项目经共享映射，不放松FK |
| `trace_link_revision` | `tenant_id, project_id, link_id, id, from_revision_id, to_revision_id, suspect, deleted_at` | 关系本身也版本化；基线包含关系修订 |

## 版本、资产及企业规则

| 对象 | 核心字段 | 必须实现的约束 |
|---|---|---|
| `asset` | `tenant_id, project_id, id, logical_name, current_revision_id` | 下载必须按资源权限重新判定 |
| `asset_revision` | `tenant_id, project_id, asset_id, id, object_key, object_version, sha256, size, scan_status` | 对象写入后校验；待扫描不可作为可信可下载资源 |
| `library` | `tenant_id, project_id, id, selection_rule_revision` | 选择规则固定；依赖闭包另算 |
| `baseline` | `tenant_id, project_id, id, state, snapshot_marker, manifest_hash, created_at` | DRAFT/CAPTURING/VALIDATING/READY/FAILED；未完成不允许固定执行 |
| `baseline_member` | `tenant_id, project_id, baseline_id, entity_kind, entity_id, revision_id, role` | 角色区分直接成员与依赖；关系和资源版本都包含 |
| `workflow_revision` | `tenant_id, project_id, workflow_id, id, definition, sandbox_policy, published_at` | 发布后不可变；脚本执行版本可追溯 |
| `project_template_revision` | `tenant_id, id, revision, metadata_manifest, policy_manifest` | 不直接写入派生项目；升级先预览 |
| `template_binding` | `tenant_id, project_id, template_id, base_revision, local_overrides` | 记录共同祖先用于三方合并 |
| `library_import` | `tenant_id, project_id, id, source_scope, source_baseline, mapping_revision` | 跨项目授权与来源映射独立 |

## 调度、集成与迁移

| 对象 | 核心字段 | 必须实现的约束 |
|---|---|---|
| `agent` | `tenant_id, id, capabilities, identity_ref, state, heartbeat_at` | 能力声明经校验；身份凭证不入数据库明文 |
| `reservation` | `tenant_id, project_id, id, resource_id, start_at, end_at, state` | 排它区间约束或串行裁定；时间区间统一半开 |
| `schedule` | `tenant_id, project_id, id, rule, timezone, misfire_policy, revision` | 明确DST和漏执行策略；改规则不改历史触发 |
| `connector` | `tenant_id, project_id, id, type, config_revision, secret_ref` | 只存secret_ref；限制出站目标 |
| `external_mapping` | `tenant_id, project_id, connector_id, entity_id, external_id, origin_marker` | 双向同步去环；不可仅凭标题匹配 |
| `migration_job` | `id, source_fingerprint, target_scope, mapping_revision, state, checkpoint` | 跨项目迁移具专用权限；来源授权可复核 |
| `migration_object_map` | `migration_id, source_kind, source_id, source_revision, target_id, target_revision, content_hash` | 唯一来源键；复跑不增对象 |
| `outbox_event` | `id, aggregate_id, tenant_id, project_id, event_type, payload_version, occurred_at, published_at` | 与业务同事务；至少一次投递 |
| `inbox_receipt` | `consumer_id, event_id, processed_at, result_hash` | 唯一键保证消费者幂等；不等于外部副作用恰好一次 |
| `idempotency_record` | `tenant_id, project_id, principal_id, route, key, request_hash, response_ref, expires_at` | 相同key不同请求体409；TTL和并发必须明确 |

## 高级能力与证据

| 对象 | 核心字段 | 必须实现的约束 |
|---|---|---|
| `business_component_revision` | `tenant_id, project_id, component_id, id, parameters, steps, automation_binding` | 接口参数版本和实现绑定 |
| `business_flow_revision` | `tenant_id, project_id, flow_id, id, component_refs, bindings` | 禁止无界循环；编译冻结组件版本 |
| `business_model_revision` | `tenant_id, project_id, model_id, id, source_format, graph, node_mapping` | 不支持语义必须显式报错 |
| `offline_package` | `tenant_id, project_id, id, manifest_id, device_binding, expires_at, sync_state` | 范围最小化；加密密钥不与包明文共存 |
| `signature_event` | `tenant_id, project_id, id, signer_id, target_revision, evidence_hash, policy_revision, signed_at, provenance` | 新签署与迁入旧签署区分；仅哈希不自动保证真实性 |
| `audit_event` | `tenant_id, project_id, id, actor, action, object_revision, before_hash, after_hash, at, provenance` | 追加写；导出可验证；持久化权限最小化 |
| `ai_job` | `tenant_id, project_id, id, prompt_revision, retrieval_scope, model_config, output_ref, review_state` | 生成建议不作为已执行证据；审查前不改业务资产 |
| `support_bundle` | `tenant_id, id, scope, redaction_policy, generated_at, expires_at` | 默认脱敏；下载需管理权限；不含秘密 |

## 关键事务和索引

需求/用例修改：锁定或CAS校验当前版本，新增不可变修订，更新当前指针，写审计和outbox，统一提交。历史修订不能在相同事务中被重新写入旧值。

运行创建：读取被批准的用例/配置/基线版本，解析资源和规则，生成canonical manifest并哈希，写run和初始attempt，同事务落地。执行端不得再次自行选择latest。

基线创建：在一致快照下获取实体、关系、依赖修订清单；对象资源按版本固定；后台打包失败可重试但不能改变已选版本。READY前验证闭包和哈希。

索引首先围绕 (tenant_id,project_id,id)、列表常用排序、当前状态、外部映射、任务可领取条件、run/attempt以及版本链建立。记录EXPLAIN和真实负载后再增加索引；不能给每个JSON键无条件建索引。

按时间分区仅适用于已验证的高量事件、运行和结果表；注意跨分区唯一约束、历史引用、归档和备份一致性。表分区不替代租户权限。

## 数据库演进顺序

V001身份与隔离；V002元数据/发布；V003稳定实体和修订；V004测试设计；V005运行及manifest；V006缺陷/追踪；V007附件和outbox；V008基线/模板；V009调度/连接器；后续高级能力各自独立编号。实际编号由迁移工具和合并流程统一，不得多人重复使用版本号。

先扩展新字段和读写兼容，后台回填并验证，再切换读取，最后独立版本删除废弃结构。破坏性删除、枚举收窄、字段类型变更不得与首次上线在同一步执行。

## 必做数据库集成测试

跨项目FK拒绝、需求树环拒绝、修订号竞争、同幂等键并发、任务过期代次拒绝、基线并发一致性、历史运行不被修改、模板三方冲突、资源区间竞争、归档后历史可解释、恢复后对象引用完整。用真实PostgreSQL执行，不用内存数据库代替这些证明。
