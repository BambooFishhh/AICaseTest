import request from './request'
import { fetchSseTicket } from './sse'

export function triggerGenerate(projectId, params) {
  return request.post(`/projects/${projectId}/generate`, params || {})
}

// v9.1(2.1): 生成流事件分发公共实现——streamGenerate / streamGenerateAppend / attachGenerate 共用。
// 事件类型：progress（进度文本）、case（单条用例）、complete（完成）、cancelled（取消）、error（失败）。
// v7.12(E16): error 事件区分"后端下发错误（e.data 有值）"与"连接层断开（e.data 为空）"，
// 后者交由 onDisconnect 降级轮询，不误报失败。
function openGenerationEventStream(url, handlers) {
  const { onProgress, onCase, onRetryReset, onComplete, onCancelled, onError, onDisconnect } = handlers
  const es = new EventSource(url)

  es.addEventListener('progress', (e) => {
    try { onProgress?.(JSON.parse(e.data).message) } catch {}
  })
  // v8.9.8(12.12): started/queued 事件——即时反馈"已接受/排队"，消灭静默黑洞
  es.addEventListener('started', (e) => {
    try { onProgress?.(JSON.parse(e.data).message) } catch {}
  })
  es.addEventListener('queued', (e) => {
    try { onProgress?.(JSON.parse(e.data).message) } catch {}
  })
  es.addEventListener('case', (e) => {
    try { onCase?.(JSON.parse(e.data).testCase) } catch {}
  })
  // v8.5: 纯信号量事件（无数据体），触发即清态
  es.addEventListener('retryReset', () => { onRetryReset?.() })
  es.addEventListener('complete', (e) => {
    try { onComplete?.(JSON.parse(e.data)) } catch {}
    es.close()
  })
  // v3.3: 取消事件（区别于 error，前端用 warning 而非 error 提示）
  es.addEventListener('cancelled', (e) => {
    try { onCancelled?.(JSON.parse(e.data).message) } catch {}
    es.close()
  })
  es.addEventListener('error', (e) => {
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

// v3.2: SSE 流式生成用例。基于浏览器原生 EventSource，返回事件源对象供调用方 close()。
// v6.6: 先以 Bearer 换短期 ticket，再用 ?ticket= 建立连接（避免长期 JWT 进 URL）。
export async function streamGenerate(projectId, handlers = {}) {
  const { data } = await fetchSseTicket()
  const url = `/api/projects/${projectId}/testcases/generate-stream?ticket=${encodeURIComponent(data.ticket)}`
  return openGenerationEventStream(url, handlers)
}

// v3.5: SSE 流式追加生成。complete 事件携带 total/appended/dropped/existingBefore 字段。
// type 为空时全类型追加；非空时仅追加该类型（positive/negative/boundary/data）。
export async function streamGenerateAppend(projectId, type, handlers = {}) {
  const params = new URLSearchParams()
  if (type) params.set('type', type)
  const query = params.toString() ? `?${params.toString()}` : ''
  const { data } = await fetchSseTicket()
  const sep = query ? '&' : '?'
  const url = `/api/projects/${projectId}/testcases/generate-stream-append${query}${sep}ticket=${encodeURIComponent(data.ticket)}`
  return openGenerationEventStream(url, handlers)
}

// v9.1(2.1): 生成任务重接——刷新/切页后重进页面时调用。
// 后端回放 agent_task_events 持久化的 progress/case 事件，任务仍在运行则续播实况广播；
// 任务已结束则按终态回放后发 complete/cancelled/error 关流。事件结构与 streamGenerate 完全一致。
export async function attachGenerate(projectId, handlers = {}) {
  const { data } = await fetchSseTicket()
  const url = `/api/projects/${projectId}/testcases/generate-stream-attach?ticket=${encodeURIComponent(data.ticket)}`
  return openGenerationEventStream(url, handlers)
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
