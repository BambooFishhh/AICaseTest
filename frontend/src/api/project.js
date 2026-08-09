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
