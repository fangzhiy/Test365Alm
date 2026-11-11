# OQ 演示闭环任务

计划与验收细则：`docs/oq-delivery-plan.md`。用户最新决定采用单Codex/codex-main开发和简化验证交付流程；用户已恢复 F-REPORT-01，G/A03 仍暂停。此清单独立于旧P0任务，只有取得实际证据才勾选。

- [x] A1 组合85单元／167集成全绿及精确清理（确切源34c19f6；总管核对scan/unit/integration/run脱敏JSON，均0F/E/S、退出0、cleanup true）。
- [x] A2 三入口真实Keycloak UI验收（确切源4a13589，run06caafafb42e4762b6f4b9f222265ab7；三条passed、1worker/0retry/0skip、exit0、cleanup/processes_stopped均true）；审查批准本地dev集成。
- [x] B1 业务字段保存/读取与不可变历史、权限、版本、事务回归（业务源da9906c，真实Linux89单元/175集成、专项8项零F/E/S；总管核对及清理确认）。
- [x] B2 用例/缺陷/需求UI字段和真实持久化验证（浏览器源b5cc1b2、run41223325ed4b445fa8afd6da363faf95；三条passed/单worker/0retry/0skip，清理及进程停止确认，详见业务字段总管复核）。
- [x] C1 实例网格负责人/状态/执行日期与权限版本回归（后端源96def34，run d3142b41；93单元/51专项/187完整集成零F/E/S，逐名库存及53资源清理由总管独立核对）。
- [x] C2 独立实际结果/结论、步骤时间和附件实际交互（浏览器源8f48057，run7dfd6f17；真实S3/ClamAV、正式双SCRAM provision及三条Keycloak UI passed，1worker/0retry/0skip，资源/秘密/进程独立核验；见执行网格总管复核）。
- [x] D1 复核状态机、指定身份和不可变修订绑定（后端源6fa15ce/run5222fc82：95单元/66专项/202完整集成零F/E/S；逐名门禁及57完整资源ID清理由总管独立核对）。
- [x] D2 复核UI、修改后需重新复核和跨身份真实验收（浏览器源afa0f4a/run77c0d60c：含指定复核人的四条真实Keycloak UI全passed，单worker零retry/skip，真实扫描/双SCRAM通过；完整资源/秘密/进程由总管独立核验，见test-case-review-coordinator-review）。
- [x] E1 可替换模拟签署提供方、再认证和不可变签署记录（实际后端源c549b25/run8eef9cc7：scan102/1→unit102→focused102/82→full102/218逐名零F/E/S；62完整资源ID清理由总管独立核验，见docs/reviews/signature-coordinator-review.md）。
- [x] E2 三种签署动作、签后锁定与新修订重新复核签署（实际UI源3af82c4/run78aa3de7：完整五条真实Keycloak UI全passed，1worker/0retry/0skip，真实扫描和双SCRAM通过；资源、秘密及进程独立核验，最终交接6c7b117仅文档变化）。
- [x] F1 三类只读报告技术交付；确切 Attempt、实例最新结果、真实偏差来源/复测周期及只读权限回归完成，待独立审核推进 main，见 docs/reviews/F-REPORT-01.md。
- [x] F2 三类中文 PDF 实际生成并逐页核验（12/6/4 页）；合成演示技术版式通过，业务格式认可仍待确认，不能当作 A03 验收。
- [ ] G1 A03十四用例独立环境真实浏览器全链路和报告核对。
- [ ] G2 最终组合完整测试、独立审查、精确清理、本地集成与路线图更新。
