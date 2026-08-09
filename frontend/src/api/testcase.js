import request from './request'

export function triggerGenerate(projectId, params) {
  return request.post(`/projects/${projectId}/generate`, params || {})
}

export function listTestCases(projectId, params) {
  return request.get(`/projects/${projectId}/testcases`, { params })
}

export function getTestCase(projectId, tcId) {
  return request.get(`/projects/${projectId}/testcases/${tcId}`)
}

export function updateTestCase(projectId, tcId, data) {
  return request.put(`/projects/${projectId}/testcases/${tcId}`, data)
}

export function deleteTestCase(projectId, tcId) {
  return request.delete(`/projects/${projectId}/testcases/${tcId}`)
}
