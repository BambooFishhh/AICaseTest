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
