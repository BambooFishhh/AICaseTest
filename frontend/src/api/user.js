import request from './request'

// v4.3: 用户查询（成员候选）
export function listUsers(keyword) {
  return request.get('/users', { params: keyword ? { keyword } : {} })
}
