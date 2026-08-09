import request from './request'

export function executeTestCase(projectId, caseId, targetUrl, mode = 'programmatic') {
  return request.post(`/projects/${projectId}/testcases/${caseId}/execute${mode === 'agent' ? '?mode=agent' : ''}`, { targetUrl })
}

export function getExecution(eid) {
  return request.get(`/executions/${eid}`)
}

// v2.1: 批量执行
export function executeBatch(projectId, caseIds, targetUrl) {
  return request.post(`/projects/${projectId}/testcases/batch-execute`, { caseIds, targetUrl })
}

// v2.1: 查询批次状态
export function getBatch(batchId) {
  return request.get(`/batches/${batchId}`)
}

export function getExecutions(projectId) {
  return request.get(`/projects/${projectId}/executions`)
}

export function getExecutionSteps(eid) {
  return request.get(`/executions/${eid}/steps`)
}
