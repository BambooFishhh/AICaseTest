import request from './request'

export function generateMindmap(projectId, data) {
  return request.post(`/projects/${projectId}/mindmap/generate`, data || {})
}

export function previewMindmap(projectId) {
  return request.get(`/projects/${projectId}/mindmap/preview`)
}

export function downloadMindmapUrl(projectId) {
  return `/api/projects/${projectId}/mindmap/download`
}
