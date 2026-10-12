# ADR-019：R06 手工执行第一切片

## 状态

已接受（R06-M09-001 范围内）。这不是完整 M09 的完成声明。

## 决策

本轮只实现项目根级测试集、已保存 MANUAL 用例修订的固定实例、一次手工运行、不可变执行清单、步骤结果、暂停/继续/完成、尝试历史和受限重跑。运行开始时在同一事务写入 `execution_manifest`、清单步骤、`execution_run`、首个 `run_attempt` 和初始 `run_step`；之后只更新当前尝试的结果和状态，不回写 `test_case` 或 `test_revision`。

所有运行读取和写入先解析项目成员，再通过事务上下文交给受限 PostgreSQL runtime 用户和 RLS。写操作使用 `Idempotency-Key`，步骤及状态转换使用正数强版本条件；完成结论由服务端根据全部步骤计算。数据库的部分唯一索引保证一个运行同时只有一个未完成尝试，事件、审计意图和 Outbox 与业务写入同事务。

## 取舍和未覆盖项

实例选择在工作台中从真实 `/tests` 与 `/revisions` 列表加载，接口仍接受 UUID 作为内部值；本轮不实现测试树、批量操作、参数/配置/环境、调度/Agent、附件、截图、缺陷关联、离线或导出。Testcontainers 的真实 PostgreSQL 验证在 CI Ubuntu 执行；本机 Windows Docker 不可用时只能记录阻塞，不能把单元测试当作数据库验收。

## 版本与迁移

新增唯一 V12 迁移，保留 V1—V11，不使用运行时自动改表。V12 由应用构建后的 Flyway 执行；迁移账号与 runtime 账号分离，runtime 不具备 DDL、清表或绕过 RLS 权限。
