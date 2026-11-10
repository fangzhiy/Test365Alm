import type { FieldDefinition } from './coreApi'
export type BusinessFieldValue = { value: string | null; fieldId?: string; revision?: number; kind?: 'SYSTEM' | 'CUSTOM'; definition?: FieldDefinition }
export type BusinessFieldValues = Record<string, BusinessFieldValue>
export type BusinessKind = 'test_case' | 'requirement' | 'defect'
export const businessLabels: Record<string,string> = {test_purpose:'测试目的',reviewer:'指定复核人',occurrence_department:'发生部门',occurrence_location:'发生地点或系统',equipment_system_id:'设备或系统编号',owning_department:'所属部门'}
export const businessKeys: Record<BusinessKind,string[]> = {test_case:['test_purpose','reviewer'],defect:['occurrence_department','occurrence_location','reviewer'],requirement:['equipment_system_id','owning_department']}
export function parseBusinessFields(value: unknown): BusinessFieldValues | undefined {
 if(value===undefined)return undefined
 if(typeof value!=='object'||value===null||Array.isArray(value))throw {code:'INVALID_RESPONSE',message:'业务字段快照无效'}
 for(const [key,v] of Object.entries(value)){
  if(!(key in businessLabels)||typeof v!=='object'||v===null||Array.isArray(v)||!('value' in v)||!(v.value===null||typeof v.value==='string'))throw {code:'INVALID_RESPONSE',message:'业务字段值无效'}
 }
 return value as BusinessFieldValues
}
