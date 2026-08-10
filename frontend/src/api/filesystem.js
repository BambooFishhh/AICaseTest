import request from './request'

// v3.1: 获取目录列表（path 为空时返回根盘符）
export function getDirs(path) {
  return request.get('/filesystem/dirs', {
    params: { path: path || undefined }
  })
}
