# F-REPORT-01 固定只读报告 v1

GET `/api/v1/projects/{projectId}/reports/runs/{runId}/attempts/{attemptId}`、
`/reports/test-sets/{setId}?status=`、`/reports/defects?testSetId=&status=&severity=&q=`。
项目 ADMIN/MEMBER/VIEWER 读取；先重验当前权限并设置 RLS。每份报告在独立 REPEATABLE_READ
只读事务中组装，不写业务、审计、Outbox 或签署挑战。响应 no-store。

报告 v1、UTC 生成时间/显示时区、projectId、范围和确切来源随 DTO 返回。打印只使用已完整加载的同一 DTO。
运行仅支持 FINISHED；其他状态明确 409 REPORT_ATTEMPT_NOT_FINISHED，不回退到最新 Attempt。
原 manifest、指定 Attempt/步骤/冻结负责人、原修订 business_fields、固定附件版本属于历史事实；
项目/测试集名称、人员当前姓名、当前偏差状态及执行之后新增复核属于生成时补充，明确标注。
实际结果与结论独立，executed_at 未记录保持未知；updated_at 仅为保存时间；开始结束跨度含暂停。

实例最新执行结果：先按 started_at DESC、run_id DESC、attempt_id DESC 选一个 Attempt，再筛选。
无 Attempt=NOT_RUN；RUNNING/PAUSED 各自分类；FINISHED 按 PASS/FAIL/BLOCKED 分类。
六类互斥；重复用例的不同实例分别计数。完成率=完成实例/匹配实例，通过率=PASS/完成实例；零分母为 null，
页面显示“—”。同时返回测试集总数、当前匹配数及匹配实例的其他历史活动 Attempt 数，不声称所有历史均完成。

偏差按后端 title 字面包含、状态、严重程度及真实来源/复测所属测试集筛选；项目范围包含手工偏差。
每偏差一行，关联和复测批量返回，不按 testcase/名称猜测。来源保留原 FAIL/BLOCKED；复测保留
resolved_revision_id 周期及绑定步骤结果，不把旧周期 PASS 当成本周期验证。
三种模拟签署只读 signature_record.payload，按原对象/修订/Attempt 绑定，保留 contentSnapshot/digest/身份/时间。
运行签署不覆盖后来补充的数据，整份动态组装报告不是“已电子签署报告”。

同步上限：1000 个匹配实例、500 个偏差、每种关联/步骤/复核集合 5000 行、整份 JSON 8MiB。
查询取上限+1 并明确 422 REPORT_LIMIT_EXCEEDED；不静默截断，不打印第一页冒充完整报告。
超限应选择较小测试集或收紧偏差筛选。无持久报告档案、公共存储链接或后台导出；重新读取可反映当前补充信息。
技术样张使用标明的合成演示数据，业务格式认可与 G/A03 真实业务验收独立。
