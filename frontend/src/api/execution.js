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

// v4.2: 取消批次执行
export function cancelBatch(batchId) {
  return request.post(`/batches/${batchId}/cancel`)
}

export function getExecutions(projectId) {
  return request.get(`/projects/${projectId}/executions`)
}

export function getExecutionSteps(eid) {
  return request.get(`/executions/${eid}/steps`)
}

// v2.9: 执行录屏视频 URL（WebM，文件流直接用作 <video :src>）
export function getExecutionVideoUrl(eid) {
  return `/api/executions/${eid}/video`
}
