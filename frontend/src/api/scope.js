import request from './request'

// v8.1: 本期范围（Scope）——识别/确认/条目管理
export function getScopeList(projectId) {
  return request.get(`/projects/${projectId}/scope`)
}

export function createScope(projectId, data) {
  return request.post(`/projects/${projectId}/scope`, data)
}

export function getGitRefs(projectId) {
  return request.get(`/projects/${projectId}/scope/git-refs`)
}

export function getScopeItems(projectId, definitionId) {
  return request.get(`/projects/${projectId}/scope/${definitionId}/items`)
}

export function addScopeItem(projectId, definitionId, data) {
  return request.post(`/projects/${projectId}/scope/${definitionId}/items`, data)
}

export function removeScopeItem(projectId, definitionId, itemId) {
  return request.delete(`/projects/${projectId}/scope/${definitionId}/items/${itemId}`)
}

export function recomputeScope(projectId, definitionId) {
  return request.post(`/projects/${projectId}/scope/${definitionId}/recompute`)
}

export function confirmScope(projectId, definitionId) {
  return request.post(`/projects/${projectId}/scope/${definitionId}/confirm`)
}

export function deleteScope(projectId, definitionId) {
  return request.delete(`/projects/${projectId}/scope/${definitionId}`)
}
