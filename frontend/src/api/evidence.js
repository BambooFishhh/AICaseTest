import request from './request'

// v13.17: 证据权威判定——冲突清单与人工裁决
// 冲突来自 PRD 与代码两条证据链的对账；裁决结果会被后续生成自动套用（同一 conflictKey）

/**
 * 冲突清单。
 *
 * @param {string} projectId 项目 id
 * @param {string} [verdict] 按裁决状态过滤（pending = 待裁决），空则全部
 * @param {boolean} [manualRequired] v13.19：true 时只取「真正需要人裁决」的项——
 *   skip（无 PRD 依据不生成）/ deferred（暂缓）虽未裁决，但不该占用人的注意力
 */
export function listEvidenceConflicts(projectId, verdict, manualRequired) {
  const params = {}
  if (verdict) params.verdict = verdict
  if (manualRequired) params.manualRequired = true
  return request.get(`/projects/${projectId}/evidence-conflicts`, { params })
}

export function submitEvidenceVerdict(projectId, conflictKey, verdict, note) {
  return request.post(`/projects/${projectId}/evidence-conflicts/${conflictKey}/verdict`, {
    verdict,
    note
  })
}
