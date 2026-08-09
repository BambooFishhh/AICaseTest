import request from './request'

export function triggerAnalysis(projectId) {
  return request.post(`/projects/${projectId}/analyze`)
}

export function getAnalysis(projectId) {
  return request.get(`/projects/${projectId}/analysis`)
}

export function getStateMachines(projectId) {
  return request.get(`/projects/${projectId}/state-machines`)
}
