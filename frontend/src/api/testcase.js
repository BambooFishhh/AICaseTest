import request from './request'
import { fetchSseTicket } from './sse'

export function triggerGenerate(projectId, params) {
  return request.post(`/projects/${projectId}/generate`, params || {})
}

// v3.2: SSE 流式生成用例。基于浏览器原生 EventSource，返回事件源对象供调用方 close()。
// 事件类型：progress（进度文本）、case（单条用例）、complete（完成，v7.1 起含 total/pushed/dropped）、cancelled（取消）、error（失败）。
// v3.3: 新增 cancelled 事件 + onCancelled 回调，区分"取消"与"失败"。
// v6.6: 先以 Bearer 换短期 ticket，再用 ?ticket= 建立连接（避免长期 JWT 进 URL）。
// v7.12(E16): 新增可选 onDisconnect 回调——error 事件区分"后端下发错误（e.data 有值）"
// 与"连接层断开（e.data 为空，网络瞬断/代理超时，后端任务仍在跑）"，后者不再误报失败。
export async function streamGenerate(projectId, { onProgress, onCase, onComplete, onCancelled, onError, onDisconnect } = {}) {
  const { data } = await fetchSseTicket()
  const url = `/api/projects/${projectId}/testcases/generate-stream?ticket=${encodeURIComponent(data.ticket)}`
  const es = new EventSource(url)

  es.addEventListener('progress', (e) => {
    try { onProgress?.(JSON.parse(e.data).message) } catch {}
  })
  es.addEventListener('case', (e) => {
    try { onCase?.(JSON.parse(e.data).testCase) } catch {}
  })
  es.addEventListener('complete', (e) => {
    // v7.1(G2): complete 携带 total/pushed/dropped（各阶段丢弃明细），推送≠落库不再静默
    try { onComplete?.(JSON.parse(e.data)) } catch {}
    es.close()
  })
  // v3.3: 取消事件（区别于 error，前端用 warning 而非 error 提示）
  es.addEventListener('cancelled', (e) => {
    try { onCancelled?.(JSON.parse(e.data).message) } catch {}
    es.close()
  })
  es.addEventListener('error', (e) => {
    // v7.12(E16): e.data 有值 = 后端下发的真实错误；为空 = 连接层断开（后端任务仍在跑）
    // 断连不再误报失败，交由 onDisconnect 降级轮询跟踪进度
    if (e.data) {
      let msg = '生成连接异常'
      try { msg = JSON.parse(e.data).message || msg } catch {}
      onError?.(msg)
    } else {
      onDisconnect?.()
    }
    es.close()
  })

  return es
}

// v3.5: SSE 流式追加生成。复用 streamGenerate 的事件结构，
// complete 事件携带 total/appended/dropped/existingBefore 字段。
// type 为空时全类型追加；非空时仅追加该类型（positive/negative/boundary/data）。
// v7.12(E16): 新增可选 onDisconnect 回调——断连（e.data 为空）不再误报失败，降级轮询。
export async function streamGenerateAppend(
  projectId,
  type,
  { onProgress, onCase, onComplete, onCancelled, onError, onDisconnect } = {}
) {
  const params = new URLSearchParams()
  if (type) params.set('type', type)
  const query = params.toString() ? `?${params.toString()}` : ''
  const { data } = await fetchSseTicket()
  const sep = query ? '&' : '?'
  const url = `/api/projects/${projectId}/testcases/generate-stream-append${query}${sep}ticket=${encodeURIComponent(data.ticket)}`
  const es = new EventSource(url)

  es.addEventListener('progress', (e) => {
    try { onProgress?.(JSON.parse(e.data).message) } catch {}
  })
  es.addEventListener('case', (e) => {
    try { onCase?.(JSON.parse(e.data).testCase) } catch {}
  })
  es.addEventListener('complete', (e) => {
    // v3.5: complete 携带 total/appended/dropped/existingBefore
    try { onComplete?.(JSON.parse(e.data)) } catch {}
    es.close()
  })
  es.addEventListener('cancelled', (e) => {
    try { onCancelled?.(JSON.parse(e.data).message) } catch {}
    es.close()
  })
  es.addEventListener('error', (e) => {
    // v7.12(E16): e.data 有值 = 后端下发的真实错误；为空 = 连接层断开（后端任务仍在跑）
    if (e.data) {
      let msg = '追加生成连接异常'
      try { msg = JSON.parse(e.data).message || msg } catch {}
      onError?.(msg)
    } else {
      onDisconnect?.()
    }
    es.close()
  })

  return es
}

// v3.3: 取消流式生成。后端置取消标志，生成线程在下个检查点停止并跳过落库。
// v3.5: 同时适用于追加生成（共用 cancellationFlags 注册表）。
export function cancelGenerate(projectId) {
  return request.post(`/projects/${projectId}/testcases/generate-cancel`)
}

export function listTestCases(projectId, params) {
  return request.get(`/projects/${projectId}/testcases`, { params })
}

// v3.6: 手动创建测试用例
export function createTestCase(projectId, data) {
  return request.post(`/projects/${projectId}/testcases`, data)
}

export function getTestCase(projectId, tcId) {
  return request.get(`/projects/${projectId}/testcases/${tcId}`)
}

export function updateTestCase(projectId, tcId, data) {
  return request.put(`/projects/${projectId}/testcases/${tcId}`, data)
}

// v4.3: 手动标记执行状态（not_executed/passed/blocked/failed）
export function updateTestCaseExecutionStatus(projectId, tcId, status) {
  return request.put(`/projects/${projectId}/testcases/${tcId}/execution-status`, { status })
}

// v5.12: 单条用例重新 AI 评审
export function reviewTestCase(projectId, tcId) {
  return request.post(`/projects/${projectId}/testcases/${tcId}/review`)
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

// v3.9: 导入 XMind 文件
export function importXmind(projectId, file) {
  const form = new FormData()
  form.append('file', file)
  return request.post(`/projects/${projectId}/testcases/import-xmind`, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
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

// v5.4: 语义搜索用例（Milvus 向量检索）
export function semanticSearch(projectId, q) {
  return request.get(`/projects/${projectId}/testcases/semantic-search`, { params: { q } })
}
