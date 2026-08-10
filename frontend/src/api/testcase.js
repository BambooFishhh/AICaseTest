import request from './request'

export function triggerGenerate(projectId, params) {
  return request.post(`/projects/${projectId}/generate`, params || {})
}

// v3.2: SSE 流式生成用例。基于浏览器原生 EventSource，返回事件源对象供调用方 close()。
// 事件类型：progress（进度文本）、case（单条用例）、complete（完成，含 total）、cancelled（取消）、error（失败）。
// v3.3: 新增 cancelled 事件 + onCancelled 回调，区分"取消"与"失败"。
export function streamGenerate(projectId, { onProgress, onCase, onComplete, onCancelled, onError } = {}) {
  const url = `/api/projects/${projectId}/testcases/generate-stream`
  const es = new EventSource(url)

  es.addEventListener('progress', (e) => {
    try { onProgress?.(JSON.parse(e.data).message) } catch {}
  })
  es.addEventListener('case', (e) => {
    try { onCase?.(JSON.parse(e.data).testCase) } catch {}
  })
  es.addEventListener('complete', (e) => {
    try { onComplete?.(JSON.parse(e.data).total) } catch {}
    es.close()
  })
  // v3.3: 取消事件（区别于 error，前端用 warning 而非 error 提示）
  es.addEventListener('cancelled', (e) => {
    try { onCancelled?.(JSON.parse(e.data).message) } catch {}
    es.close()
  })
  es.addEventListener('error', (e) => {
    // SSE 原生 error 事件：data 可能为空（网络断开）或携带后端 error 事件 data
    let msg = '生成连接异常'
    if (e.data) {
      try { msg = JSON.parse(e.data).message || msg } catch {}
    }
    onError?.(msg)
    es.close()
  })

  return es
}

// v3.3: 取消流式生成。后端置取消标志，生成线程在下个检查点停止并跳过落库。
export function cancelGenerate(projectId) {
  return request.post(`/projects/${projectId}/testcases/generate-cancel`)
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

export function batchDeleteTestCases(projectId, ids) {
  return request.delete(`/projects/${projectId}/testcases/batch`, { data: { ids } })
}

// v1.7: 导出用例（JSON/CSV）。用 fetch 拿到 headers（文件名）和 blob
export async function exportTestCases(projectId, format, ids) {
  const params = new URLSearchParams({ format })
  if (ids && ids.length) {
    params.set('ids', ids.join(','))
  }
  const resp = await fetch(
    `/api/projects/${projectId}/testcases/export?${params.toString()}`
  )
  if (!resp.ok) {
    const msg = await resp.text().catch(() => '')
    throw new Error(`导出失败(${resp.status}): ${msg || resp.statusText}`)
  }
  const disposition = resp.headers.get('content-disposition') || ''
  const match = disposition.match(/filename="?([^"]+)"?/)
  const fileName = match ? decodeURIComponent(match[1]) : `testcases.${format}`
  const blob = await resp.blob()
  return { data: blob, fileName }
}

// v1.7: 导入 JSON 用例文件
export function importTestCases(projectId, file) {
  const form = new FormData()
  form.append('file', file)
  return request.post(`/projects/${projectId}/testcases/import`, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// v1.7: 跨项目复制用例
export function copyToProject(projectId, ids, targetProjectId) {
  return request.post(`/projects/${projectId}/testcases/copy-to`, {
    ids,
    targetProjectId
  })
}

// v1.8: 批量改评审状态
export function reviewTestCases(projectId, ids, status, reviewer) {
  return request.post(`/projects/${projectId}/testcases/review`, {
    ids,
    status,
    reviewer
  })
}

// v1.9: 用例版本列表
export function listTestCaseVersions(projectId, testcaseId) {
  return request.get(`/projects/${projectId}/testcases/${testcaseId}/versions`)
}

// v1.9: 用例版本详情（含快照）
export function getTestCaseVersion(projectId, testcaseId, versionId) {
  return request.get(`/projects/${projectId}/testcases/${testcaseId}/versions/${versionId}`)
}

// v1.9: 回滚到指定版本
export function rollbackTestCaseVersion(projectId, testcaseId, versionId) {
  return request.post(`/projects/${projectId}/testcases/${testcaseId}/versions/${versionId}/rollback`)
}
