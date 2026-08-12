import request from './request'

// v4.0: 认证接口
export function register(data) {
  return request.post('/auth/register', data)
}

export function login(data) {
  return request.post('/auth/login', data)
}

export function getMe() {
  return request.get('/auth/me')
}

// v4.1: 修改密码
export function changePassword(data) {
  return request.post('/auth/change-password', data)
}
