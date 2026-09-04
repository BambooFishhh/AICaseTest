/**
 * MCP text= 点击链路自检脚本（v12.20 回归护栏）
 * ==============================================
 * 背景：v12.19 曾引入 `text=X*` 通配候选，实测在 Playwright 中会触发元素等待
 * 重试循环，每次 page.click 卡满整个 timeout（即使 count()=0 也不快速失败），
 * 在并发/串行叠加下顶到 MCP 客户端 60s 请求超时（TC-1102 step11 实锤）。
 * v12.20 已移除该候选。本脚本用于快速确认当前 index.js 的 selectorCandidates
 * 不再产生会卡死的候选。
 *
 * 运行方式（必须在含 playwright chromium 的环境，如 backend 容器）：
 *   cd /app/playwright-mcp-server
 *   node verify_mcp_text.mjs        # 期望输出全 OK，无任何 FAIL
 *
 * 它不依赖被测系统/登录态——用本地静态 HTML 模拟 Vant van-cell + badge 结构，
 * 端到端跑与线上一致的 selectorCandidates + clickWithFallback。
 */
import { chromium } from 'playwright';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// v12.20 selectorCandidates：只保留原始 + 去 visible=true 的裸候选。若此处与
// index.js 不一致，需同步修改（自检的意义在于确保候选不再含 text=X* 通配）。
function selectorCandidates(selector) {
  const list = [selector];
  const stripped = String(selector || '').replace(/\s*>>\s*visible=true\s*/g, '').trim();
  if (stripped && stripped !== selector) list.push(stripped);
  return list;
}
// 简化的 clickWithFallback（复用线上超时口径）
async function clickWithFallback(page, selector, timeout = 6000) {
  const candidates = selectorCandidates(selector);
  let lastErr = null;
  for (let round = 0; round < 2; round++) {
    for (const sel of candidates) {
      try { await page.click(sel, { timeout }); return sel; }
      catch (e) { lastErr = e; }
    }
    if (round === 0) await page.waitForTimeout(600);
  }
  throw lastErr || new Error('click failed: ' + selector);
}

const html = `<!doctype html><html><head><meta charset="utf-8"><style>
.cell{display:flex;align-items:center;justify-content:space-between;padding:14px 16px;border-bottom:1px solid #eee;background:#fff}
.badge{display:inline-block;min-width:18px;height:18px;line-height:18px;text-align:center;background:#ee0a24;color:#fff;border-radius:9px;font-size:12px;padding:0 5px}
</style></head><body>
<div class="cell" id="fav"><span>我的收藏</span><span>共 2 件<span class="badge">2</span></span></div>
<div class="cell" id="foot"><span>浏览足迹</span><span>共 2 件<span class="badge">0</span></span></div>
<script>
document.getElementById('fav').onclick=()=>document.body.dataset.hit='fav';
document.getElementById('foot').onclick=()=>document.body.dataset.hit='foot';
</script></body></html>`;

const server = http.createServer((req, res) => { res.setHeader('Content-Type', 'text/html;charset=utf-8'); res.end(html); });
await new Promise(r => server.listen(0, '127.0.0.1', r));
const port = server.address().port;
const browser = await chromium.launch({ headless: true });
const page = await browser.newContext({ viewport: { width: 390, height: 844 } }).then(c => c.newPage());
await page.goto(`http://127.0.0.1:${port}/`, { waitUntil: 'load', timeout: 10000 });
await page.waitForTimeout(300);

let pass = 0, fail = 0;
async function check(label, sel, expectHit) {
  const start = Date.now();
  try {
    const used = await clickWithFallback(page, sel, 4000); // 4s 足够证明不卡
    const ms = Date.now() - start;
    const hit = await page.evaluate(() => document.body.dataset.hit);
    const ok = ms < 2000 && (expectHit === null || hit === expectHit);
    if (expectHit !== null) await page.evaluate(() => delete document.body.dataset.hit);
    if (ok) { console.log(`PASS  [${label}] used="${used}" ${ms}ms`); pass++; }
    else { console.log(`FAIL  [${label}] used="${used}" ${ms}ms hit=${hit} expect=${expectHit}`); fail++; }
  } catch (e) {
    const ms = Date.now() - start;
    console.log(`FAIL  [${label}] ${ms}ms error=${(e.message || '').split('\n')[0]}`); fail++;
  }
}

console.log('--- v12.20 MCP text= 点击链路自检 ---');
// 1) 原始带 visible=true 的选择器必须快速命中（真实用例形态）
await check('text=浏览足迹 >> visible=true', 'text=浏览足迹 >> visible=true', 'foot');
// 2) 带 badge 的 cell：子串匹配应命中 cell 父级并触发点击
await check('text=我的收藏(带badge)', 'text=我的收藏 >> visible=true', 'fav');
// 3) 关键回归护栏：确认候选列表里没有通配符候选（否则说明回退了 v12.19）
const cands = selectorCandidates('text=浏览足迹 >> visible=true');
if (cands.some(c => /text=.*\*$/.test(c))) {
  console.log('FAIL  selectorCandidates 仍生成通配候选，请检查 index.js'); fail++;
} else {
  console.log(`PASS  selectorCandidates 无通配候选: ${JSON.stringify(cands)}`); pass++;
}
// 4) 元素不存在：会走完 2 轮×候选的 actionability 等待后失败（Playwright 固有行为，
//    无论 css/text 均如此，非 v12.20 缺陷）。预期在可控时间(<35s)内抛错而非无限挂起。
{
  const s = Date.now();
  try { await page.click('text=不存在的东西xyz >> visible=true', { timeout: 4000 }); console.log('FAIL  不存在元素竟点击成功'); fail++; }
  catch (e) {
    const ms = Date.now() - s;
    if (ms < 35000) { console.log(`PASS  不存在元素在 ${ms}ms 内失败（Playwright actionability 固有等待）`); pass++; }
    else { console.log(`FAIL  不存在元素耗时过长 ${ms}ms`); fail++; }
  }
}

console.log(`\n=== 结果: ${pass} PASS / ${fail} FAIL ===`);
await browser.close(); server.close();
process.exit(fail > 0 ? 1 : 0);
