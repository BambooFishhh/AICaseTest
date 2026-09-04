/**
 * AICaseTest Playwright MCP Server
 * 独立浏览器操作服务，基于 Playwright 实现真正的视频录屏。
 *
 * v2.7: 浏览器操作 + 截图 + 坐标点击 + 视频录屏
 * v7.11(E12): 多会话隔离——全局单例 browser/context/page 改为 sessions Map，
 *             全部工具增加可选 session_id 参数（缺省 "default"）。
 *             修复：项目并发执行配额 >1 时，多任务共用同一浏览器导致页面互踩、
 *             取消任务全局 stopRecording/browser_close 误杀并发任务的录屏与浏览器。
 *
 * 环境变量：无（浏览器配置通过工具参数传入）
 *
 * 工具（13个）：
 *   browser_launch, browser_navigate, browser_take_screenshot,
 *   browser_visual_click, browser_dom_click, browser_fill, browser_key_press,
 *   browser_scroll, browser_add_cookies, browser_get_page_status,
 *   browser_video_get_path, browser_video_save, browser_close
 */

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';
import { chromium, devices } from 'playwright';
import fs from 'fs';
import path from 'path';

// v7.11(E12): sessionId -> { browser, context, page }
const sessions = new Map();

function sessionIdOf(args) {
  return (args && args.session_id) || 'default';
}

function getSession(id) {
  const s = sessions.get(id || 'default');
  if (!s || !s.page) {
    throw new Error(`会话不存在或已关闭: ${id || 'default'}`);
  }
  return s;
}

async function closeSessionInternal(id) {
  const s = sessions.get(id);
  if (!s) return;
  sessions.delete(id);
  if (s.page) await s.page.close().catch(() => {});
  if (s.context) await s.context.close().catch(() => {});
  if (s.browser) await s.browser.close().catch(() => {});
}

async function clearClickMarker(page) {
  if (!page) return;
  await page.evaluate(() => {
    document.querySelectorAll('[data-agent-marker]').forEach((el) => el.remove())
  }).catch(() => {})
}

async function markPoint(page, x, y) {
  if (!page) return;
  await page.evaluate(({ x, y }) => {
    const style = 'position:fixed;pointer-events:none;z-index:999999;background:#ef4444;'
    const circle = document.createElement('div')
    circle.setAttribute('data-agent-marker', '1')
    circle.style.cssText = `${style}left:${x - 24}px;top:${y - 24}px;width:48px;height:48px;border:3px solid #ef4444;border-radius:50%;background:rgba(239,68,68,.12);box-shadow:0 0 0 2px rgba(255,255,255,.85);`
    const h = document.createElement('div')
    h.setAttribute('data-agent-marker', '1')
    h.style.cssText = `${style}left:${x - 40}px;top:${y - 2}px;width:80px;height:4px;`
    const v = document.createElement('div')
    v.setAttribute('data-agent-marker', '1')
    v.style.cssText = `${style}left:${x - 2}px;top:${y - 40}px;width:4px;height:80px;`
    document.body.appendChild(circle)
    document.body.appendChild(h)
    document.body.appendChild(v)
  }, { x, y })
}

async function markSelector(page, selector) {
  await clearClickMarker(page)
  if (!page) return null
  // v12.20: boundingBox 设 3s 上限，避免 text= 通配/歧义选择器在元素 attach 等待上
  // 无限拖慢（历史曾把整个 MCP 调用拖到客户端 60s 超时）。仅用于打点标记，失败不阻塞点击。
  const box = await page.locator(selector).first().boundingBox({ timeout: 3000 }).catch(() => null)
  if (!box) return null
  const x = Math.round(box.x + box.width / 2)
  const y = Math.round(box.y + box.height / 2)
  await markPoint(page, x, y)
  return { x, y }
}

// v12.17(P1): 选择器候选序列——首个候选超时/未命中时按序退化重试。
// `text=X >> visible=true` 在无可见同名节点时会落空，退回裸 `text=X` 至少保留
// 原有行为；瞬时 loading 遮罩造成的 actionability 失败也靠末尾的重试吸收。
// v12.20(修复 v12.19 回归): 移除 "text=X*" 通配候选。
// 实测（容器内 playwright chromium，模拟 Vant van-cell + badge 结构）：
//   `text=X >> visible=true` 命中 1、点击 ~60ms OK；
//   `text=X`           命中 1、点击 ~45ms OK；
//   `text=X*`          无论 count() 为 0 还是命中隐藏同名节点，page.click 都会
//                     卡满整个 timeout（5000ms，非 no-element 快速失败）——通配
//                     正则选择器触发 Playwright 内部元素等待的重试循环。在
//                     clickWithFallback 2 轮 × N 候选下成倍放大，叠加 markSelector
//                     与 MCP 客户端串行开销顶到 60s 请求超时（实测 TC-1102 step11）。
// 带 badge 文本（"我的收藏 2"）实际由 `text=我的收藏` 的子串匹配命中 cell 父级，
// 无需通配符；故通配候选纯属负优化，直接移除。
function selectorCandidates(selector) {
  const list = [selector]
  const stripped = String(selector || '').replace(/\s*>>\s*visible=true\s*/g, '').trim()
  if (stripped && stripped !== selector) list.push(stripped)
  return list
}

// v12.18(P0): 单次尝试超时 10s→6s——clickWithFallback 最坏路径（2 轮×2 候选）从 ~40s
// 收敛到 ~24s，避免并发执行时顶到 MCP 客户端 60s 请求超时（实测 54 次 tools/call 60s 超时）
async function clickWithFallback(page, selector, timeout = 6000) {
  const candidates = selectorCandidates(selector)
  let lastErr = null
  for (let round = 0; round < 2; round++) {
    for (const sel of candidates) {
      try {
        await page.click(sel, { timeout })
        return sel
      } catch (e) {
        lastErr = e
      }
    }
    if (round === 0) await page.waitForTimeout(600) // 等瞬时遮罩/动画散去
  }
  throw lastErr || new Error(`click failed: ${selector}`)
}

// v13.12(checkbox 坐标点击兜底): vant 等组件库的 checkbox/switch 其 change 事件只绑在
// 自定义 icon 上，Playwright page.click() 的 actionability 命中点对这类组件不生效
// （实测点击后 aria-checked 仍为 false）。此兜底改为真实鼠标坐标点击 icon
// （page.mouse.click 实测可正确触发勾选）。
async function tryCheckboxCoordinateClick(page, selector) {
  try {
    // 定位到 checkbox 容器元素本身（可 evaluate 读状态），而非仅坐标
    const cbLoc = await resolveCheckboxLocator(page, selector)
    if (!cbLoc) return null
    // 点击前读"该元素"勾选状态（用 locator 读，避免扫全页第一个误读）
    const beforeChecked = await cbLoc.getAttribute('aria-checked')
    // 等可能遮住 click 的瞬时 vant 动画/toast 散去（真实长执行里点击瞬间常被过渡遮罩挡住，
    // 导致 mouse.click 落在遮罩上不触发 change——probe 短会话稳定后才点故能勾上）
    await page.waitForTimeout(400)
    const box = await cbLoc.boundingBox({ timeout: 3000 }).catch(() => null)
    if (!box) return null
    const x = Math.round(box.x + Math.min(9, box.width * 0.5))
    const y = Math.round(box.y + box.height / 2)
    // 点击 + 校验翻转，最多重试 2 次（每次间隔等动画）
    let afterChecked = null
    for (let attempt = 0; attempt < 3; attempt++) {
      await page.mouse.click(x, y)
      await page.waitForTimeout(400)
      afterChecked = await cbLoc.getAttribute('aria-checked')
      if (afterChecked !== null && beforeChecked !== afterChecked) break
    }
    return { x, y, target: selector, checkedBefore: beforeChecked === 'true', checkedAfter: afterChecked === 'true' }
  } catch (e) {
    return null
  }
}

// v13.12: 解析目标选择器命中的 checkbox 容器 locator（可精确读其 aria-checked）
async function resolveCheckboxLocator(page, selector) {
  const candidates = [
    `${selector} >> xpath=ancestor::*[contains(@class,'van-checkbox') or @role='checkbox'][1]`,
    `${selector} >> role=checkbox`,
    `${selector} .van-checkbox`,
    `${selector} >> xpath=ancestor-or-self::*[contains(@class,'van-checkbox') or @role='checkbox'][1]`,
  ]
  for (const c of candidates) {
    try {
      const loc = page.locator(c).first()
      if (await loc.count() > 0) return loc
    } catch (e) { /* 试下一候选 */ }
  }
  return null
}

// v13.12: 判断目标选择器解析到的元素是否属 vant checkbox 家族（决定是否跳过 page.click 走坐标）
async function probeCheckboxCandidate(page, selector) {
  try {
    const checks = [
      `${selector} >> role=checkbox`,
      `${selector} >> xpath=ancestor::*[contains(@class,'van-checkbox') or @role='checkbox'][1]`,
      `${selector} .van-checkbox`,
      `${selector} .van-switch`,
    ]
    for (const c of checks) {
      try {
        if (await page.locator(c).first().count() > 0) return true
      } catch (e) { /* 忽略 */ }
    }
    if (String(selector).includes('.van-checkbox') || String(selector).includes('role=checkbox')
        || String(selector).includes('checkbox')) return true
    return false
  } catch (e) {
    return false
  }
}

async function fillWithFallback(page, selector, value, timeout = 6000) {
  const candidates = selectorCandidates(selector)
  let lastErr = null
  for (let round = 0; round < 2; round++) {
    for (const sel of candidates) {
      try {
        await page.fill(sel, value, { timeout })
        return sel
      } catch (e) {
        lastErr = e
      }
    }
    if (round === 0) await page.waitForTimeout(600)
  }
  throw lastErr || new Error(`fill failed: ${selector}`)
}

// v7.11(E12): 所有工具共用的可选 session_id 参数描述
const sessionIdProperty = {
  type: 'string',
  description: '会话 ID（缺省 default）',
};

const server = new Server(
  { name: 'aicasetest-playwright-mcp', version: '1.1.0' },
  { capabilities: { tools: {} } }
);

// 列出工具
server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: 'browser_launch',
      description: '启动浏览器会话。传入 video_dir 则启用视频录屏。v7.11: 支持多会话，session_id 区分。',
      inputSchema: {
        type: 'object',
        properties: {
          headless: { type: 'boolean', default: true },
          width: { type: 'integer', default: 1280 },
          height: { type: 'integer', default: 720 },
          device: { type: 'string', description: 'Playwright 设备预设名（如 iPhone 14）；传入且存在时启用设备模拟，否则回落宽高' },
          video_dir: { type: 'string', description: '视频保存目录（传入则启用录屏）' },
          session_id: sessionIdProperty,
        },
      },
    },
    {
      name: 'browser_navigate',
      description: '导航到指定 URL。',
      inputSchema: {
        type: 'object',
        properties: { url: { type: 'string' }, session_id: sessionIdProperty },
        required: ['url'],
      },
    },
    {
      name: 'browser_go_back',
      description: '浏览器后退（history back），用于"返回上一页"类步骤。',
      inputSchema: {
        type: 'object',
        properties: { session_id: sessionIdProperty },
      },
    },
    {
      name: 'browser_take_screenshot',
      description: '截图并保存到指定路径（PNG）。',
      inputSchema: {
        type: 'object',
        properties: { path: { type: 'string', description: '截图保存的绝对路径' }, session_id: sessionIdProperty },
        required: ['path'],
      },
    },
    {
      name: 'browser_visual_click',
      description: '按坐标点击页面。',
      inputSchema: {
        type: 'object',
        properties: {
          x: { type: 'integer' },
          y: { type: 'integer' },
          session_id: sessionIdProperty,
        },
        required: ['x', 'y'],
      },
    },
    {
      name: 'browser_dom_click',
      description: '按 CSS 选择器点击元素。',
      inputSchema: {
        type: 'object',
        properties: { selector: { type: 'string', description: 'CSS 选择器' }, session_id: sessionIdProperty },
        required: ['selector'],
      },
    },
    {
      name: 'browser_fill',
      description: '向输入框填入文本。',
      inputSchema: {
        type: 'object',
        properties: {
          selector: { type: 'string', description: 'CSS 选择器' },
          value: { type: 'string', description: '要填入的文本' },
          session_id: sessionIdProperty,
        },
        required: ['selector', 'value'],
      },
    },
    {
      name: 'browser_key_press',
      description: '向当前聚焦元素发送键盘按键。',
      inputSchema: {
        type: 'object',
        properties: {
          key: { type: 'string', description: '按键名，例如 Enter' },
          session_id: sessionIdProperty,
        },
        required: ['key'],
      },
    },
    {
      name: 'browser_scroll',
      description: '上下滚动当前页面。',
      inputSchema: {
        type: 'object',
        properties: {
          direction: { type: 'string', enum: ['down', 'up', 'top', 'bottom'] },
          amount: { type: 'integer', description: '滚动像素，默认 600' },
          session_id: sessionIdProperty,
        },
        required: ['direction'],
      },
    },
    {
      name: 'browser_add_cookies',
      description: '向浏览器上下文注入登录 Cookie。',
      inputSchema: {
        type: 'object',
        properties: {
          cookies: {
            type: 'array',
            items: { type: 'object' },
            description: 'Playwright Cookie 数组，例如 [{name,value,url|domain,path}]',
          },
          session_id: sessionIdProperty,
        },
        required: ['cookies'],
      },
    },
    {
      name: 'browser_get_page_status',
      description: '获取当前页面状态（url/title/textSnippet）。',
      inputSchema: { type: 'object', properties: { session_id: sessionIdProperty } },
    },
    {
      // v8.9.7(临时): 注入 localStorage（如 litemall_token），解决 token 型前端登录态（cookie 注入不适用）
      name: 'browser_set_storage',
      description: '向当前页面 localStorage 批量写入键值（如登录 token），需先 browser_navigate 到目标源。',
      inputSchema: {
        type: 'object',
        properties: {
          storage: {
            type: 'array',
            items: { type: 'object' },
            description: 'localStorage 键值数组，例如 [{key:"litemall_token",value:"xxx"}]',
          },
          session_id: sessionIdProperty,
        },
        required: ['storage'],
      },
    },
    {
      name: 'browser_video_get_path',
      description: '获取录屏视频临时文件路径。',
      inputSchema: { type: 'object', properties: { session_id: sessionIdProperty } },
    },
    {
      name: 'browser_video_save',
      description: '保存录屏视频到指定路径（WebM 格式）。保存后会话终结（浏览器关闭）。',
      inputSchema: {
        type: 'object',
        properties: {
          filename: { type: 'string', description: '视频保存路径（.webm）' },
          session_id: sessionIdProperty,
        },
        required: ['filename'],
      },
    },
    {
      name: 'browser_close',
      description: '关闭指定会话的浏览器，释放资源。',
      inputSchema: { type: 'object', properties: { session_id: sessionIdProperty } },
    },
  ],
}));

// 调用工具
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;
  const sid = sessionIdOf(args);

  try {
    switch (name) {
      case 'browser_launch': {
        // 同 session_id 重复 launch：先关闭旧会话，避免浏览器进程泄漏
        if (sessions.has(sid)) {
          await closeSessionInternal(sid);
        }
        const browser = await chromium.launch({ headless: args.headless ?? true });
        // v8.9.7(临时): 支持移动设备模拟——传 device 且存在预设时用 devices[device]（含视口/isMobile/touch/UA），
        // 否则回落纯宽高桌面模式。默认不传即保持原桌面行为，便于后续切回 B 端。
        const device = args.device && devices[args.device] ? devices[args.device] : null;
        const viewW = device ? device.viewport.width : (args.width ?? 1280);
        const viewH = device ? device.viewport.height : (args.height ?? 720);
        let ctxOpts;
        if (device) {
          ctxOpts = { ...device, viewport: { width: viewW, height: viewH } };
        } else {
          ctxOpts = { viewport: { width: viewW, height: viewH } };
        }
        if (args.video_dir) {
          fs.mkdirSync(args.video_dir, { recursive: true });
          ctxOpts.recordVideo = { dir: args.video_dir, size: { width: viewW, height: viewH } };
        }
        const context = await browser.newContext(ctxOpts);
        const page = await context.newPage();
        sessions.set(sid, { browser, context, page });
        // v7.11(E12): 返回会话 ID（原返回 'ok'，调用方无法感知会话标识）
        return { content: [{ type: 'text', text: sid }] };
      }

      case 'browser_navigate': {
        const { page } = getSession(sid);
        await clearClickMarker(page)
        await page.goto(args.url, { waitUntil: 'load', timeout: 30000 });
        await page.waitForTimeout(1000);
        return { content: [{ type: 'text', text: page.url() }] };
      }

      case 'browser_take_screenshot': {
        const { page } = getSession(sid);
        const buf = await page.screenshot();
        const dir = path.dirname(args.path);
        if (dir && !fs.existsSync(dir)) {
          fs.mkdirSync(dir, { recursive: true });
        }
        fs.writeFileSync(args.path, buf);
        return { content: [{ type: 'text', text: args.path }] };
      }

      case 'browser_visual_click': {
        const { page } = getSession(sid);
        await clearClickMarker(page)
        await markPoint(page, args.x, args.y)
        await page.waitForTimeout(500)
        await page.mouse.click(args.x, args.y);
        await page.waitForTimeout(1000);
        return { content: [{ type: 'text', text: `clicked (${args.x},${args.y})` }] };
      }

      case 'browser_go_back': {
        const { page } = getSession(sid);
        await page.goBack({ timeout: 10000 });
        await page.waitForTimeout(1000);
        return { content: [{ type: 'text', text: JSON.stringify({ url: page.url() }) }] };
      }

      case 'browser_dom_click': {
        const { page } = getSession(sid);
        const pos = await markSelector(page, args.selector)
        await page.waitForTimeout(500)
        let clickedSel = null, clickedPos = pos || { x: 0, y: 0 }, checkedAfter = null
        // v13.12: vant checkbox/switch 等自定义组件，其 change 只绑在 icon 上，page.click
        // 会"静默成功但不生效"（实测 aria-checked 不变）。若目标可解析为 checkbox 家族
        // 则直接走坐标点击（唯一可靠路径），跳过 page.click。
        const cbAhead = await probeCheckboxCandidate(page, args.selector)
        if (cbAhead) {
          const cbPos = await tryCheckboxCoordinateClick(page, args.selector)
          if (cbPos) {
            clickedSel = 'checkbox-coordinate@' + cbPos.target
            clickedPos = { x: cbPos.x, y: cbPos.y }
            checkedAfter = cbPos.checkedAfter
          }
        }
        if (!clickedSel) {
          try {
            clickedSel = await clickWithFallback(page, args.selector, 6000);
          } catch (clickErr) {
            // 兜底：page.click 抛异常时也试一次 checkbox 坐标点击
            const cbPos = await tryCheckboxCoordinateClick(page, args.selector)
            if (cbPos) {
              clickedSel = 'checkbox-coordinate@' + cbPos.target
              clickedPos = { x: cbPos.x, y: cbPos.y }
              checkedAfter = cbPos.checkedAfter
            } else {
              throw clickErr
            }
          }
        }
        await page.waitForTimeout(1000);
        const resp = { clicked: clickedSel, x: clickedPos.x, y: clickedPos.y }
        if (checkedAfter !== null) resp.checkboxChecked = checkedAfter
        return { content: [{ type: 'text', text: JSON.stringify(resp) }] };
      }

      case 'browser_fill': {
        const { page } = getSession(sid);
        const fillPos = await markSelector(page, args.selector)
        await page.waitForTimeout(500)
        // v12.17(P1): 同 dom_click，输入也走候选回退
        // v12.18(P0): 10s→6s，与点击同口径收敛
        await fillWithFallback(page, args.selector, args.value, 6000);
        await page.waitForTimeout(600);
        const filled = fillPos || { x: 0, y: 0 };
        return { content: [{ type: 'text', text: JSON.stringify({ filled: args.selector, x: filled.x, y: filled.y }) }] };
      }

      case 'browser_key_press': {
        const { page } = getSession(sid);
        await page.keyboard.press(args.key);
        await page.waitForTimeout(800);
        return { content: [{ type: 'text', text: `pressed ${args.key}` }] };
      }

      case 'browser_scroll': {
        const { page } = getSession(sid);
        const amount = args.amount || 600;
        if (args.direction === 'down') {
          await page.mouse.wheel(0, amount);
        } else if (args.direction === 'up') {
          await page.mouse.wheel(0, -amount);
        } else if (args.direction === 'top') {
          await page.evaluate(() => window.scrollTo(0, 0));
        } else if (args.direction === 'bottom') {
          await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
        }
        await page.waitForTimeout(600);
        return { content: [{ type: 'text', text: `scrolled ${args.direction}` }] };
      }

case 'browser_add_cookies': {
          const { context } = getSession(sid);
          await context.addCookies(args.cookies || []);
          return { content: [{ type: 'text', text: `added ${(args.cookies || []).length} cookies` }] };
        }

        // v8.9.7(临时): 注入 localStorage（token 型前端登录态）
        case 'browser_set_storage': {
          const { page } = getSession(sid);
          const storage = args.storage || [];
          for (const item of storage) {
            if (!item || item.key == null) continue;
            await page.evaluate(({ k, v }) => { localStorage.setItem(k, v == null ? '' : String(v)); }, { k: String(item.key), v: item.value });
          }
          return { content: [{ type: 'text', text: `set ${storage.length} localStorage keys` }] };
        }

      case 'browser_get_page_status': {
        const { page } = getSession(sid);
        const bodyText = await page.innerText('body').catch(() => '');
        const status = {
          url: page.url(),
          title: await page.title(),
          textSnippet: bodyText.substring(0, 2000),
        };
        return { content: [{ type: 'text', text: JSON.stringify(status) }] };
      }

      case 'browser_video_get_path': {
        const { page } = getSession(sid);
        const video = page.video();
        if (video) {
          const videoPath = await video.path();
          return { content: [{ type: 'text', text: videoPath }] };
        }
        return { content: [{ type: 'text', text: 'no video' }] };
      }

      case 'browser_video_save': {
        const s = sessions.get(sid);
        if (!s || !s.page) {
          return { content: [{ type: 'text', text: 'no video' }] };
        }
        const video = s.page.video();
        if (video) {
          const dir = path.dirname(args.filename);
          if (dir && !fs.existsSync(dir)) {
            fs.mkdirSync(dir, { recursive: true });
          }
          // 先关闭 context，让 Playwright 落盘完整 WebM，避免只生成 0 字节文件
          if (s.context) {
            await s.context.close().catch(() => {});
          }
          await video.saveAs(args.filename);
          // v7.11(E12): 视频保存后会话终结——从 Map 移除，浏览器进程随 context/browser 关闭释放
          await closeSessionInternal(sid);
          return { content: [{ type: 'text', text: args.filename }] };
        }
        return { content: [{ type: 'text', text: 'no video' }] };
      }

      case 'browser_close': {
        // v7.11(E12): 只关闭指定会话，不再全局杀浏览器（并发任务互不影响）
        if (sessions.has(sid)) {
          await closeSessionInternal(sid);
          return { content: [{ type: 'text', text: `closed ${sid}` }] };
        }
        return { content: [{ type: 'text', text: 'already closed' }] };
      }

      default:
        throw new Error(`未知工具: ${name}`);
    }
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Playwright MCP 错误: ${error.message}` }],
      isError: true,
    };
  }
});

// 启动 server（stdio 传输）
const transport = new StdioServerTransport();
await server.connect(transport);
console.error('[Playwright MCP Server] 启动成功 v1.1 | tools=13 | 多会话隔离已启用');
