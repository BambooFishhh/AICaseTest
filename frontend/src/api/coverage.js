import request from './request'

export function getCoverageMatrix(projectId) {
  return request.get(`/projects/${projectId}/coverage/matrix`)
}
