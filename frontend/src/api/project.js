import request from './request'

export function listProjects() {
  return request.get('/projects')
}

export function createProject(data) {
  return request.post('/projects', data)
}

export function getProject(id) {
  return request.get(`/projects/${id}`)
}

export function deleteProject(id) {
  return request.delete(`/projects/${id}`)
}

// v1.10: 查询 PRD
export function getPrd(projectId) {
  return request.get(`/projects/${projectId}/prd`)
}

// v1.10: 更新文本 PRD
export function updatePrd(projectId, prdContent) {
  return request.put(`/projects/${projectId}/prd`, { prdContent })
}

// v1.10: 上传 PDF
export function uploadPrdPdf(projectId, file) {
  const form = new FormData()
  form.append('file', file)
  return request.post(`/projects/${projectId}/prd/upload`, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// v1.10: 抓取在线链接
export function fetchPrdUrl(projectId, url) {
  return request.post(`/projects/${projectId}/prd/fetch`, { url })
}

// v5.9: 项目上下文（额外 Prompt + 上下文文档）
export function getProjectContext(projectId) {
  return request.get(`/projects/${projectId}/context`)
}

export function updateProjectContext(projectId, payload) {
  return request.put(`/projects/${projectId}/context`, payload)
}

// v5.9: 执行 Cookie 读写
export function getExecutionCookies(projectId) {
  return request.get(`/projects/${projectId}/execution-cookies`)
}

export function updateExecutionCookies(projectId, cookies) {
  return request.put(`/projects/${projectId}/execution-cookies`, { cookies })
}

// v3.4: 获取生成参数
export function getGenerationParams(projectId) {
  return request.get(`/projects/${projectId}/generation-params`)
}

// v3.4: 更新生成参数
export function updateGenerationParams(projectId, params) {
  return request.put(`/projects/${projectId}/generation-params`, params)
}

// v3.15: 多执行环境
export function getExecutionEnvironments(projectId) {
  return request.get(`/projects/${projectId}/environments`)
}

export function updateExecutionEnvironments(projectId, payload) {
  return request.put(`/projects/${projectId}/environments`, payload)
}
