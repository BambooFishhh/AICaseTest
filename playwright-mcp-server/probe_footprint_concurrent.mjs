// v13.12 D1 诊断探针 v2：4 路并发复现"全选/删除选中 6s timeout"（只读诊断，拦截删除接口）
// 运行：docker exec test-agent-backend sh -c 'cd /app/playwright-mcp-server && node probe_footprint_concurrent.mjs'
import { chromium } from 'playwright';

const BASE = 'http://172.31.160.1:6255';
const ROUNDS = 3;
const CONCURRENCY = 4;

async function getToken() {
  const r = await fetch(BASE + '/wx/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'user123', password: 'user123' })
  });
  const j = await r.json();
  const token = j && j.data && j.data.token;
  if (!token) throw new Error('login failed: ' + JSON.stringify(j).slice(0, 200));
  return token;
}

async function oneContext(token, tag) {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  // 安全网：拦截足迹/收藏删除接口，杜绝并发探针误删数据
  await ctx.route('**/footprint/deleteBatch*', r => r.abort());
  await ctx.route('**/footprint/delete*', r => r.abort());
  await ctx.route('**/collect/delete*', r => r.abort());
  await ctx.addInitScript(t => localStorage.setItem('litemall_token', t), token);
  const page = await ctx.newPage();
  const t0 = Date.now();
  const result = { tag, nav: 0, shanchu: '', quanxuan: '', fatal: '' };
  try {
    await page.goto(BASE + '/#/footprint', { waitUntil: 'domcontentloaded', timeout: 15000 });
    result.nav = Date.now() - t0;
    // 先点删除选中（无选中 → 仅 toast"请先选择"，无数据变更），测 6s 立即点击
    try {
      await page.click('text=删除选中', { timeout: 6000 });
      result.shanchu = 'ok@' + (Date.now() - t0 - result.nav) + 'ms';
    } catch (e) { result.shanchu = 'TIMEOUT'; }
    // 再点全选（仅本地勾选，配合上面的接口拦截无双删风险）
    try {
      await page.click('text=全选', { timeout: 6000 });
      result.quanxuan = 'ok@' + (Date.now() - t0 - result.nav) + 'ms';
    } catch (e) { result.quanxuan = 'TIMEOUT'; }
  } catch (e) {
    result.fatal = e.message.slice(0, 80);
  }
  await browser.close();
  return result;
}

const token = await getToken();
console.log('[token] ok');
let timeoutCount = 0, totalClicks = 0;
for (let r = 1; r <= ROUNDS; r++) {
  const t0 = Date.now();
  const results = await Promise.all(
    Array.from({ length: CONCURRENCY }, (_, k) => oneContext(token, `r${r}-c${k}`))
  );
  for (const res of results) {
    for (const key of ['shanchu', 'quanxuan']) {
      totalClicks++;
      if (String(res[key]).startsWith('TIMEOUT')) timeoutCount++;
    }
    console.log(`[${res.tag}] nav=${res.nav}ms 删除选中=${res.shanchu} 全选=${res.quanxuan} ${res.fatal}`);
  }
  console.log(`-- round ${r} done in ${Date.now() - t0}ms`);
}
console.log(`[SUMMARY] clicks=${totalClicks} timeouts=${timeoutCount}`);
