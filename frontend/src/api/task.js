import request from './request'

// v5.3: 任务队列统计（生成/执行 排队与运行计数）
export function getTaskStats() {
  return request.get('/tasks/stats')
}
