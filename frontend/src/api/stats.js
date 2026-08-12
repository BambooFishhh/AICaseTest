import request from './request'

// v3.17: 全局统计（仪表盘）
export function getStatsOverview() {
  return request.get('/stats/overview')
}
