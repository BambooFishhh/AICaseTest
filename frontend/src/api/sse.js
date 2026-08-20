import request from './request'

// v6.6: 通过 Authorization: Bearer 换取短期 SSE ticket，避免长期 JWT 进入 URL
export function fetchSseTicket() {
  return request.post('/sse/ticket')
}
