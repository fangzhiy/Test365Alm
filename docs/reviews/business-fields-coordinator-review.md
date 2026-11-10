# 业务字段总管复核

2026-11-11；业务源 `da9906c6967edd53bfde5808eb405a47b7106571`，浏览器源 `b5cc1b2a7e4e6ded36c9ce83551bf6296132aa94`，前置基线 `8bd7408e3c7dc1f65ed19721d40c98136b0fa5b7`。后端与修复后的真实浏览器证据均已独立核对，总管批准本地dev集成B范围；C及后续OQ功能另行验收。

## 已核对的后端证据

工作区 `feat-business-fields` 的忽略证据 `local-evidence/linux-tests/16077b4faf6c450287f50b31103f07d2/`。总管独立读取scan/unit/focused/integration/run脱敏JSON并检查逐方法身份无重复、每项状态及汇总一致：

| 阶段 | 单元 | 集成 | 结果 |
|---|---:|---:|---|
| 真实扫描预检 | 89 | 1 | 零failure/error/skip，exit0 |
| 单元 | 89 | — | 零failure/error/skip，exit0 |
| 新DB/非空升级专项 | 89 | 8 | 零failure/error/skip，exit0 |
| 唯一完整集成 | 89 | 175 | 零failure/error/skip，exit0 |

run.json固定上述源SHA，exit0、cleanup=true。此前52e7735专项夹具错误及7433ef2两处旧冻结DTO相等失败报告保留，不能改写为通过；当前绿色来自修复后的新提交完整执行。

## 首轮真实浏览器结果

同一da9906c源，证据 `local-evidence/local-browser/bb35d43bc533419a9fc4ee1f9c07db1e/`。总管独立读取run/results：缺陷FAIL→复测PASS→关闭及历史流程passed；手工执行/恢复/完成/重跑流程passed；用例创建/步骤编辑/历史/viewer只读流程failed。workers=1、retries=0，每条retry=0，无skip；exit1、cleanup=true、processes_stopped=true。B正在定位失败，不将两条通过视为整组验收。

保留本轮失败证据，修复固定新SHA后再执行真实浏览器验收；如仅修改测试选择器/断言且运行源码一致，无需无关重跑后端；如修改业务源码，必须重新确定验证范围及旧结果适用边界。

## 最终真实浏览器与验收决定

b5cc1b2只把用例创建/重载的两处复核人定位改为精确combobox可访问名称，原业务断言不变。总管核对git树确认后端/前端业务源码、服务端测试、设施、CI和契约与da9906c相同；独立集成a2b45d2的完整apps/server、apps/web、tools、CI和contracts树与b5cc1b2相同。

新证据 `local-evidence/local-browser/41223325ed4b445fa8afd6da363faf95/`：总管独立读取run/results，三条确切用例/执行/缺陷流程均passed且expected=passed，workers=1、retries=0，每条retry=0、无skip，exit0、cleanup=true、processes_stopped=true。覆盖真实姓名选择、三实体字段保存重载及不可变历史。总管另外核对desktop-linux Engine为a6f010ac-61a2-4544-b133-c2b0d92d11d3，按该run标签查询container/network/volume均为空。

最终worker交接b2d9ec24f33241d7c4de182c74070743bd568806仅新增经验与契约/交接结果说明，不改变被测运行源码；原文归档 `docs/reviews/business-fields-worker-handoff.md`。B1/B2按当前交付计划验收通过，Docker时段释放给C。独立V25启动故障探针和完整14条浏览器全集本轮NOT_RUN，不由三条绿色推断通过。

Trail图已刷新；源集成检查点a2b45d2的check未出现扫描截断，只有BUSINESS_METHODS被误判为重引入集成失败。该集合实际要求八个确切方法，门禁拒绝非零F/E/S、缺失/重复，且新确切源89/175已全部通过；误报解释写入合并提交。最终累计对dev检查达到400KB上限，不能声称全部扫描；最后文档/经验增量另以a2b45d2为base检查，运行/测试/设施树逐项一致性核对不依赖该文本扫描。

## 已复核的关键边界

- 三类业务字段存于确切内容修订，冻结SYSTEM或同项目CUSTOM定义与值；旧定义变更不重新解释历史。省略时创建为空、编辑继承，显式空对象清空；输入类型与未知属性检查保持。
- 原授权、强版本、持久幂等、审计/Outbox与内容写入继续同事务；省略业务字段保持旧请求hash。重放在现有授权入口之后，不用可变最新内容代替冻结结果。
- 复核人值为稳定本地主体UUID；候选、规范化和DB触发器统一检查有效项目writer。候选函数固定search_path并核对actor/scope，只暴露同项目候选身份/姓名。viewer可以读取但不能成为新指定可批准人。后续D必须在动作时重新授权。
- 新正式迁移仅V23；旧V1—V22保持。历史内容夹具与固定61711294源机械比对，SQL和行为保留，只有测试类名/组件移除/父构造/兼容DTO复用等适配；只在旧目标上下文显式注册。升级原非空快照/幂等断言保留，latest后验证正式服务。
- e08140f仅在四类响应DTO规范化NullNode为Java null；输入显式null仍拒绝。7749951把新字段快照按实际JSON往返规范化数字节点，JSON值不变、不回写历史；单元往返及真实DB同修订no-op断言覆盖该根因。
- 复核人界面使用授权目录姓名，历史明确标注“当前成员姓名”，不可用身份不回显UUID；da9906c仅替换审计复核人值单元，保留原父面板刷新生命周期。

## 集成范围与后续

- C合流时保留B所有旧表原列快照和C执行新增列投影/hash重放检查；AssetProvisioning同时保留legacy requirement与legacy attachment注册，latest后用正式服务。组合计数逐名复核，B单线175不能充当B+C全绿结果。
- D基于总管指定的B合流提交实现复核；稳定reviewer身份必须于动作事务再次检查资格。批准B字段保存不等于D复核状态机或签署已完成。
