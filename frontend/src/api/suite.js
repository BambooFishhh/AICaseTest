import request from './request'

// v3.15: 测试集/回归集
export function createSuite(projectId, data) {
  return request.post(`/projects/${projectId}/test-suites`, data)
}

export function listSuites(projectId) {
  return request.get(`/projects/${projectId}/test-suites`)
}

export function deleteSuite(projectId, suiteId) {
  return request.delete(`/projects/${projectId}/test-suites/${suiteId}`)
}

export function executeSuite(projectId, suiteId, targetUrl) {
  return request.post(`/projects/${projectId}/test-suites/${suiteId}/execute`, { targetUrl })
}
