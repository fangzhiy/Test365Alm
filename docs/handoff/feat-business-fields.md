# 交接：feat-business-fields（B／路线图1c）

基线61711294ff10e16b8df29418badaa8b455bc80ea。检查点：48f22e5契约、7e9ba53修订持久化、6831005前端、1322b99升级回归与V25探针；6710fde已按总管指令合入dev@1dd7330。未推送、未PR、未操作dev。两个自动配置文件保留工作区修改，不纳入提交。

## 已实现与接口

精简契约：contracts/business-fields.md。原实体创建／修订API新增businessFields；不是metadata validate端点持久化。字段值与definition/kind/fieldId/revision一起冻结在test_revision、defect_revision、requirement_revision.business_fields。省略表示新建为空或编辑继承；显式{}清空。字段变更创建内容新修订，原强版本、持久幂等及重新授权、RLS、审计／Outbox事务保持。旧客户端省略新字段时保持既有请求哈希编码。

用例test_purpose/reviewer；缺陷occurrence_department/occurrence_location/reviewer；需求equipment_system_id/owning_department。description仍为一般描述，test_purpose为独立目的。SYSTEM默认定义可直接保存；表单可读取现有FieldDefinitionService同键CUSTOM定义并显式采用确切修订，由FieldRules做类型、必填、默认值与边界验证。修改metadata不回填历史。

后续D的身份入口为test_revision.business_fields.reviewer.value：本地主体UUID，非显示名／邮箱。写入时服务与DB调用app_has_active_project_member，覆盖principal/tenant_member/project_member有效性；D动作必须重新检查指定主体当时有效，且批准绑定确切test_revision.id，不能读取当前实体字段代替该修订。此分支没有复核状态机。

前端tests/requirements/defects序列化和响应解析保留字段；三个表单及真实历史显示已接入；冲突后草稿保留。FolderTree和HistoryPanel保留，HistoryService最小扩展业务字段差异（值及冻结定义），这是与其他审计改动的合流点。没有修改execution模块或TestExecutionPanel；新增DB回归核对运行manifest在用例字段修订前后完全相同。

## 实际检查

- Java21全单元89通过，零失败／错误／跳过；新集成测试已编译，尚未执行。
- 合入最新dev后前端257通过；此前255通过（dev新增2项localOwnership回归）。npm lint退出0，9个既有警告；build通过。
- 合入dev后Python离线161通过；此前155通过。
- trail check通过，只有既有非阻断提示。
- Docker集成与真实浏览器NOT_RUN；总管已分配下一串行Docker时段，当前未启动任何资源。

## 下一步与合流

新增BusinessFieldsDatabaseIT六个方法、BusinessFieldsMigrationUpgradeIT一个方法，总集成预期167+7=174，单元85+4=89；这是源码方法审计与Windows单元结果，不能冒充Linux实际报告。固定Linux runner仍硬编码85/167，scan阶段也要求85单元，会在实际89单元时拒绝继续；任务明令本worker不要改linux_test_inside.py计数或重写runner，需总管给出设施计数更新或批准的兼容入口后运行确切SHA。

正式迁移只新增V23，旧V1—V22未改；V24保留执行工作线；故意失败探针改V25，正式版本数当前23。Linux runner未改。共享旧升级测试中latest迁移数量断言／旧target下服务验证可能需要总管合流处理，不静默放宽；先实际跑全套定位并报告。

在串行时段先取得新增真实DB／非空升级证据，再全量；真实ClamAV/EICAR与资源Engine/标签/精确ID守卫保持，最后清理确认并释放时段。禁止日常.env、W1演示、5173/8080、Defender修改、假GITHUB变量。后端JDK21路径见AGENTS.md。

## 复核人选择检查点

总管要求正常流程按姓名选择同事。新增 reviewer-candidates 同项目只读有限目录（不复用管理员 member-candidates），排除失效身份，SQL再次核对actor/scope；当前/历史从目录解析当前姓名并标注，失效明确不可用，不显示内部UUID。业务快照仍仅保存稳定ID，不冒充历史姓名。新增DB边界方法 reviewerDirectoryIsReadableByMembersBoundedSearchableAndRejectsForeignOrInactiveScope，BusinessFieldsDatabaseIT现7方法，加原升级1方法：预期89单元/175集成；逐名计数待同步独立设施提交。Windows Java21单元89、前端261、lint及build通过；Docker与真实浏览器仍NOT_RUN。

3307c7f按总管受限授权独立更新原89/174计数；ef2e083已合dev@8bd7408。当前审查确认旧target升级测试在旧schema调用当前内容服务不再成立；修复将保留旧target快照断言，先用明确冻结的旧服务测试夹具构造真实非空旧数据，再在旧目标迁移核验后升级latest，才调用当前服务。不得修改旧迁移、删断言或在生产服务动态探测schema。
