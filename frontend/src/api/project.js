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
