// v8.9.5(12.7): 短请求容量冒烟——登录 + 项目列表（纯 DB/HTTP，不消耗 LLM token）
// 用法：
//   docker run --rm --network host -v ${PWD}\perf\k6:/scripts grafana/k6:0.54.0 run /scripts/short-requests.js
// 场景参数按需调整（VU/时长）；SSE 生成与执行场景需 mock LLM 供应商支撑 [需确认]，另行补充。

import http from 'k6/http'
import { check, sleep } from 'k6'

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8000'
const USERNAME = __ENV.K6_USER || 'admin'
const PASSWORD = __ENV.K6_PASS || ''

export const options = {
  vus: Number(__ENV.VUS || 30),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
}

let token = ''

export function setup() {
  if (!PASSWORD) {
    // 未提供账号时退化为仅健康检查场景
    return { healthOnly: true }
  }
  const res = http.post(`${BASE}/api/auth/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } })
  check(res, { 'login 200': (r) => r.status === 200 })
  token = res.json('data.token') || ''
  return { healthOnly: false }
}

export default function (data) {
  check(http.get(`${BASE}/api/health`), { 'health 200': (r) => r.status === 200 })
  if (!data.healthOnly && token) {
    const res = http.get(`${BASE}/api/projects`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    check(res, { 'projects 200': (r) => r.status === 200 })
    sleep(1)
  } else {
    sleep(0.2)
  }
}
