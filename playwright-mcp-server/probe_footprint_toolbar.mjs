// v13.12 D1 诊断探针：/footprint 工具栏(全选/删除选中)渲染时延测量 + 立即点击复现
// 运行方式：docker exec test-agent-backend sh -c 'cd /app/playwright-mcp-server && node probe_footprint_toolbar.mjs'
import { chromium } from 'playwright';

const BASE = 'http://172.31.160.1:6255';
const TRIALS = 5;

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

const token = await getToken();
console.log('[token] ok, len=' + token.length);

for (let i = 1; i <= TRIALS; i++) {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  await ctx.addInitScript(t => localStorage.setItem('litemall_token', t), token);
  const page = await ctx.newPage();
  const t0 = Date.now();
  try {
    await page.goto(BASE + '/#/footprint', { waitUntil: 'domcontentloaded', timeout: 15000 });
    const navMs = Date.now() - t0;

    // A) 工具栏渲染时延（waitForSelector，上限 20s）
    let toolbarMs = -1;
    try {
      await page.waitForSelector('text=全选', { state: 'visible', timeout: 20000 });
      toolbarMs = Date.now() - t0;
    } catch (e) {
      console.log(`#${i} toolbar NOT visible within 20s (nav=${navMs}ms)`);
    }

    // B) 模拟执行器现状：立即 page.click text=全选 6s 上限（不带 waitForSelector）
    let clickImmediate = 'ok';
    const t1 = Date.now();
    try {
      await page.click('text=全选', { timeout: 6000 });
    } catch (e) {
      clickImmediate = 'TIMEOUT(' + (Date.now() - t1) + 'ms)';
    }

    // C) 若 A 已等到工具栏：显式等待后再 click 的耗时
    let clickAfterWait = 'n/a';
    if (toolbarMs >= 0) {
      const t2 = Date.now();
      try {
        await page.click('text=全选 >> visible=true', { timeout: 6000 });
        clickAfterWait = (Date.now() - t2) + 'ms';
      } catch (e) {
        clickAfterWait = 'TIMEOUT';
      }
    }

    console.log(`#${i} nav=${navMs}ms toolbarVisible=${toolbarMs}ms clickImmediate=${clickImmediate} clickAfterWait=${clickAfterWait}`);
  } catch (e) {
    console.log(`#${i} FATAL ${e.message.slice(0, 120)}`);
  } finally {
    await browser.close();
  }
}
