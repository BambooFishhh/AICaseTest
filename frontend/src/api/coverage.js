import request from './request'

export function getCoverageMatrix(projectId) {
  return request.get(`/projects/${projectId}/coverage/matrix`)
}

// v7.15(3b): 未覆盖接口清单——代码分析出的接口中无用例引用/执行覆盖的部分
export function getUncoveredEndpoints(projectId) {
  return request.get(`/projects/${projectId}/coverage/uncovered-endpoints`)
}
