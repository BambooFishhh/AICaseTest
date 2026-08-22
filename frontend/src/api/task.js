import request from './request'

// v5.3: 任务队列统计（生成/执行 排队与运行计数）
export function getTaskStats() {
  return request.get('/tasks/stats')
}

// v6.7: 高可用任务中心（仅 ADMIN）
export function listTasks(params) {
  return request.get('/admin/tasks', { params })
}

export function getTask(id) {
  return request.get(`/admin/tasks/${id}`)
}

export function retryTask(id) {
  return request.post(`/admin/tasks/${id}/retry`)
}
