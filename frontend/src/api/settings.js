import request from './request'

export function getSettings() {
  return request.get('/settings')
}

export function updateSettings(data) {
  return request.put('/settings', data)
}

export function testLlm() {
  return request.post('/settings/test-llm')
}

// v3.17: 系统级默认生成参数
export function getDefaultGenerationParams() {
  return request.get('/settings/generation-params')
}

export function updateDefaultGenerationParams(data) {
  return request.put('/settings/generation-params', data)
}
