import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8000';
const VU_COUNT = Number(__ENV.VU_COUNT || 20);

export const options = {
  stages: [
    { duration: '1m', target: VU_COUNT },
    { duration: '2m', target: VU_COUNT },
    { duration: '1m', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
  },
};

export function setup() {
  const login = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({
    username: __ENV.K6_ADMIN_USER || 'admin',
    password: __ENV.K6_ADMIN_PASSWORD || 'admin123',
  }), { headers: { 'Content-Type': 'application/json' } });
  check(login, { 'login 200': (r) => r.status === 200 });
  return { token: login.json('data.token') };
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` };
  const health = http.get(`${BASE_URL}/api/health`);
  const projects = http.get(`${BASE_URL}/api/projects`, { headers });
  const tasks = http.get(`${BASE_URL}/api/tasks/stats`, { headers });
  check(health, { 'health 200': (r) => r.status === 200 });
  check(projects, { 'projects 200': (r) => r.status === 200 });
  check(tasks, { 'tasks stats 200': (r) => r.status === 200 });
  sleep(1);
}
