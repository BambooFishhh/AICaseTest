import request from './request'

export function generateMindmap(projectId) {
  return request.post(`/projects/${projectId}/mindmap/generate`)
}

export function previewMindmap(projectId) {
  return request.get(`/projects/${projectId}/mindmap/preview`)
}

export function downloadMindmapUrl(projectId) {
  return `/api/projects/${projectId}/mindmap/download`
}
