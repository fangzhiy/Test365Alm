# 业务字段纵向切片（1c）

扩展现有实体创建/修订命令，`businessFields` 是字段键到 `{fieldId?,revision?,value}` 的完整快照；省略表示新建为空、编辑继承上一修订，显式 `{}` 清空。禁止未知字段键、未知属性、错误 JSON 类型。实体修订响应包含冻结的 `businessFields`，历史读取不使用当前字段定义重新解释。

| 实体 | 键 | 原生字段映射 |
|---|---|---|
| test_case | test_purpose, reviewer | description 仍为一般描述，test_purpose 为独立测试目的；reviewer 为本地主体 UUID |
| defect | occurrence_department, occurrence_location, reviewer | description、来源快照及状态均保留原义 |
| requirement | equipment_system_id, owning_department | title/body/priority 保留原义 |

未配置时使用版本化 SYSTEM 定义（TEXT，可空，目的4000字符、其他文本200字符、reviewer36字符）。项目可通过既有 FieldDefinitionService 创建同键 CUSTOM 定义；发送其 fieldId 和确切正整数 revision 后按 FieldRules 验证类型/必填/默认值/边界。冻结结果含 definition、kind、fieldId、revision、value；SYSTEM 字段不接受客户端伪造定义，CUSTOM 必须同项目同键。已有快照可原样提交，服务端只信任字段定义库中的确切修订。字段定义改变不回填旧实体；采用新定义必须显式写入新内容修订。

reviewer 只接受有效同项目管理员或普通写成员的本地主体 UUID，服务端与数据库检查 principal、project_member、tenant_member 的停用/撤权/有效期；不接受显示名作为身份。未来复核状态机应取 test_revision.business_fields.reviewer.value，并在动作时再次授权；本切片不实现复核状态。

字段直接保存在 test_revision / defect_revision / requirement_revision 的 JSONB 列；写入与内容、步骤、审计、Outbox、持久幂等同事务。字段变化必须创建新内容修订，测试用例在封存前一次写完整；缺陷状态修订继承冻结字段，旧内容修订与运行 manifest 不变。现有强 If-Match、RLS、CSRF、幂等冻结重放与重新授权保持。

正式迁移仅 V23，旧 V1—V22 不改；失败探针统一 V25，V24 由执行工作线使用，因此本分支正式迁移数量为23而非最大探针版本25。总管已明确授权按逐方法审计更新 Linux runner 的精确计数，不能降低门槛或改执行命令。

验证包括 FieldRules/快照纯逻辑、控制器未知字段与类型、前端草稿/重放/历史、真实临时 PostgreSQL 非空 V22→V23 升级、runtime 直接 SQL 非法引用/越权和不可变历史。固定业务源码 da9906c 已通过真实 Linux scan89/1、unit89、focused89/8、full89/175，零失败/错误/跳过并精确清理；确切源码、浏览器结果、历史失败及未执行范围见 `docs/handoff/feat-business-fields.md`，离线测试不能冒充真实验证。

复核人 UI 通过只读 GET /api/v1/projects/{projectId}/reviewer-candidates 选择有效项目成员：search 最多200字符（字面子串），limit 1..100（默认25），UUID keyset cursor，principalId 可精确解析已保存身份。服务先要求项目读取权限并设置事务 RLS；最小 SECURITY DEFINER 仅返回同项目有效主体的 principalId/displayName，独立核对当前 actor/scope，无成员管理权限。显示名来自当前授权目录，不写入冻结业务值；历史明确标注“当前成员姓名”，撤权/停用/失效/不可读则显示“已指定的复核人不可用”，不会回显 UUID。D 仍按被冻结的稳定身份在动作时重新授权。

B/D资格统一复用既有 SQL `app_has_active_project_writer(tenant_id,project_id,principal_id)`：有效 PROJECT_ADMIN/PROJECT_MEMBER 才能被指定，PROJECT_VIEWER 可以查询候选/读取历史但不可被新指定；tenant角色不代替项目资格。BusinessFields.normalize、V23完整性触发器与候选目录均调用该函数；D应在已有项目授权边界内于批准事务再次调用它。既有冻结幂等响应可能没有 businessFields 或为 null，前端按未提供兼容，不能拿当前值替代旧响应。
