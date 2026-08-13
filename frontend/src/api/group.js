import request from './request'

// v4.3: 项目组
export function listGroups() {
  return request.get('/groups')
}

export function createGroup(data) {
  return request.post('/groups', data)
}

export function updateGroup(groupId, data) {
  return request.put(`/groups/${groupId}`, data)
}

export function deleteGroup(groupId) {
  return request.delete(`/groups/${groupId}`)
}

export function listGroupMembers(groupId) {
  return request.get(`/groups/${groupId}/members`)
}

export function addGroupMember(groupId, data) {
  return request.post(`/groups/${groupId}/members`, data)
}

export function updateGroupMemberRole(groupId, userId, role) {
  return request.put(`/groups/${groupId}/members/${userId}`, { role })
}

export function removeGroupMember(groupId, userId) {
  return request.delete(`/groups/${groupId}/members/${userId}`)
}
