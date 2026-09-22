# P0 迁移缺口报告

状态：`NOT_RUN`  
前置条件：三类已授权脱敏样本、源版本登记、只读提取方式和附件访问权限。

## 记录格式

每条缺口至少填写：源系统、项目、实体类型、源 ID/修订、目标去向、缺口类型、影响、证据位置、处理决定、负责人和复核日期。

| gap_id | source_kind | entity_kind | source_revision | gap_type | impact | disposition | evidence | owner | status |
|---|---|---|---|---|---|---|---|---|---|
| GAP-PLACEHOLDER | UNCONFIRMED | UNCONFIRMED | UNCONFIRMED | AWAIT_AUTHORIZED_SAMPLE | 未评估 | OPEN |  | 迁移负责人 | NOT_RUN |

## 必查项目

- 稳定身份、历史修订和作者映射是否可取得。
- 需求、测试、实例、运行、步骤、缺陷、关系和基线是否能闭包导出。
- 附件内容、对象版本和 SHA-256 是否可取得并与数据库清单对账。
- 时区、业务日期、签名来源、删除和归档语义是否可解释。
- 重复导入是否通过来源指纹和对象映射保持幂等。
- 无法提取的对象是否进入隔离、旧系统只读存档或明确不支持清单。

没有真实样本时，所有项目保持 `NOT_RUN`，不生成数量、完整率或兼容结论。
