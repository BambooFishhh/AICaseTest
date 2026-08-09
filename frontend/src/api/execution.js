import request from './request'

export function executeTestCase(projectId, caseId, targetUrl) {
  return request.post(`/projects/${projectId}/testcases/${caseId}/execute`, { targetUrl })
}

export function getExecution(eid) {
  return request.get(`/executions/${eid}`)
}

export function getExecutions(projectId) {
  return request.get(`/projects/${projectId}/executions`)
}

export function getExecutionSteps(eid) {
  return request.get(`/executions/${eid}/steps`)
}
