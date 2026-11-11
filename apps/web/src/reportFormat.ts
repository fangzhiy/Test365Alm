import { object } from './reportsApi'
import type { Json, Row } from './reportsApi'
export const text = (v: Json | undefined): string => v === null || v === undefined || v === ''
  ? '未记录 / 未知' : typeof v === 'object' ? JSON.stringify(v) : String(v)
export const row = (v: Json | undefined): Row => object(v) ? v : {}
export const labels: Record<string, string> = {
  NOT_RUN: '未执行', RUNNING: '执行中', PAUSED: '已暂停', PASS: '通过', FAIL: '失败', BLOCKED: '受阻', FINISHED: '已完成',
  OPEN: '待处理', IN_PROGRESS: '处理中', RESOLVED: '已解决', CLOSED: '已关闭', REOPENED: '已重开',
  LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重',
  TEST_APPROVAL: '用例批准', RUN_COMPLETION: '运行完成确认', DEVIATION_CLOSURE: '偏差关闭确认',
  RUN_STEP: '执行步骤来源', REQUIREMENT: '需求关联', SUBMIT: '提交复核', APPROVE: '批准', REJECT: '拒绝',
}
export const label = (v: Json | undefined) => typeof v === 'string' && labels[v] ? `${labels[v]} (${v})` : text(v)
export const time = (v: Json | undefined) => typeof v === 'string' && Number.isFinite(Date.parse(v))
  ? `${new Date(v).toISOString().replace('T', ' ').replace(/Z$/, '')} UTC` : '未记录 / 未知'
export const rate = (v: Json | undefined) => typeof v === 'number' ? `${(v * 100).toFixed(1)}%` : '—'
