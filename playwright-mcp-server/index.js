/**
 * AICaseTest Playwright MCP Server
 * 独立浏览器操作服务，基于 Playwright 实现真正的视频录屏。
 *
 * v2.7: 浏览器操作 + 截图 + 坐标点击 + 视频录屏
 *
 * 环境变量：无（浏览器配置通过工具参数传入）
 *
 * 工具（9个）：
 *   browser_launch, browser_navigate, browser_take_screenshot,
 *   browser_visual_click, browser_dom_click, browser_get_page_status,
 *   browser_video_get_path, browser_video_save, browser_close
 */

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';

let browser = null;
let context = null;
let page = null;

async function clearClickMarker() {
  if (!page) return
  await page.evaluate(() => {
    document.querySelectorAll('[data-agent-marker]').forEach((el) => el.remove())
  }).catch(() => {})
}

async function markPoint(x, y) {
  if (!page) return
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

async function markSelector(selector) {
  await clearClickMarker()
  if (!page) return null
  const box = await page.locator(selector).first().boundingBox().catch(() => null)
  if (!box) return null
  const x = Math.round(box.x + box.width / 2)
  const y = Math.round(box.y + box.height / 2)
  await markPoint(x, y)
  return { x, y }
}

const server = new Server(
  { name: 'aicasetest-playwright-mcp', version: '1.0.0' },
  { capabilities: { tools: {} } }
);

// 列出工具
server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: 'browser_launch',
      description: '启动浏览器。传入 video_dir 则启用视频录屏。',
      inputSchema: {
        type: 'object',
        properties: {
          headless: { type: 'boolean', default: true },
          width: { type: 'integer', default: 1280 },
          height: { type: 'integer', default: 720 },
          video_dir: { type: 'string', description: '视频保存目录（传入则启用录屏）' },
        },
      },
    },
    {
      name: 'browser_navigate',
      description: '导航到指定 URL。',
      inputSchema: {
        type: 'object',
        properties: { url: { type: 'string' } },
        required: ['url'],
      },
    },
    {
      name: 'browser_take_screenshot',
      description: '截图并保存到指定路径（PNG）。',
      inputSchema: {
        type: 'object',
        properties: { path: { type: 'string', description: '截图保存的绝对路径' } },
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
        },
        required: ['x', 'y'],
      },
    },
    {
      name: 'browser_dom_click',
      description: '按 CSS 选择器点击元素。',
      inputSchema: {
        type: 'object',
        properties: { selector: { type: 'string', description: 'CSS 选择器' } },
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
        },
        required: ['cookies'],
      },
    },
    {
      name: 'browser_get_page_status',
      description: '获取当前页面状态（url/title/textSnippet）。',
      inputSchema: { type: 'object', properties: {} },
    },
    {
      name: 'browser_video_get_path',
      description: '获取录屏视频临时文件路径。',
      inputSchema: { type: 'object', properties: {} },
    },
    {
      name: 'browser_video_save',
      description: '保存录屏视频到指定路径（WebM 格式）。',
      inputSchema: {
        type: 'object',
        properties: { filename: { type: 'string', description: '视频保存路径（.webm）' } },
        required: ['filename'],
      },
    },
    {
      name: 'browser_close',
      description: '关闭浏览器，释放资源。',
      inputSchema: { type: 'object', properties: {} },
    },
  ],
}));

// 调用工具
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  try {
    switch (name) {
      case 'browser_launch': {
        browser = await chromium.launch({ headless: args.headless ?? true });
        const ctxOpts = {
          viewport: { width: args.width ?? 1280, height: args.height ?? 720 },
        };
        if (args.video_dir) {
          fs.mkdirSync(args.video_dir, { recursive: true });
          ctxOpts.recordVideo = {
            dir: args.video_dir,
            size: { width: args.width ?? 1280, height: args.height ?? 720 },
          };
        }
        context = await browser.newContext(ctxOpts);
        page = await context.newPage();
        return { content: [{ type: 'text', text: 'ok' }] };
      }

      case 'browser_navigate': {
        await clearClickMarker()
        await page.goto(args.url, { waitUntil: 'load', timeout: 30000 });
        await page.waitForTimeout(1000);
        return { content: [{ type: 'text', text: page.url() }] };
      }

      case 'browser_take_screenshot': {
        const buf = await page.screenshot();
        const dir = path.dirname(args.path);
        if (dir && !fs.existsSync(dir)) {
          fs.mkdirSync(dir, { recursive: true });
        }
        fs.writeFileSync(args.path, buf);
        return { content: [{ type: 'text', text: args.path }] };
      }

      case 'browser_visual_click': {
        await clearClickMarker()
        await markPoint(args.x, args.y)
        await page.waitForTimeout(500)
        await page.mouse.click(args.x, args.y);
        await page.waitForTimeout(1000);
        return { content: [{ type: 'text', text: `clicked (${args.x},${args.y})` }] };
      }

      case 'browser_dom_click': {
        const pos = await markSelector(args.selector)
        await page.waitForTimeout(500)
        await page.click(args.selector, { timeout: 10000 });
        await page.waitForTimeout(1000);
        const clicked = pos || { x: 0, y: 0 };
        return { content: [{ type: 'text', text: JSON.stringify({ clicked: args.selector, x: clicked.x, y: clicked.y }) }] };
      }

      case 'browser_fill': {
        const fillPos = await markSelector(args.selector)
        await page.waitForTimeout(500)
        await page.fill(args.selector, args.value);
        await page.waitForTimeout(600);
        const filled = fillPos || { x: 0, y: 0 };
        return { content: [{ type: 'text', text: JSON.stringify({ filled: args.selector, x: filled.x, y: filled.y }) }] };
      }

      case 'browser_key_press': {
        await page.keyboard.press(args.key);
        await page.waitForTimeout(800);
        return { content: [{ type: 'text', text: `pressed ${args.key}` }] };
      }

      case 'browser_scroll': {
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
        await context.addCookies(args.cookies || []);
        return { content: [{ type: 'text', text: `added ${(args.cookies || []).length} cookies` }] };
      }

      case 'browser_get_page_status': {
        const bodyText = await page.innerText('body').catch(() => '');
        const status = {
          url: page.url(),
          title: await page.title(),
          textSnippet: bodyText.substring(0, 500),
        };
        return { content: [{ type: 'text', text: JSON.stringify(status) }] };
      }

      case 'browser_video_get_path': {
        const video = page.video();
        if (video) {
          const videoPath = await video.path();
          return { content: [{ type: 'text', text: videoPath }] };
        }
        return { content: [{ type: 'text', text: 'no video' }] };
      }

      case 'browser_video_save': {
        const video = page?.video();
        if (video) {
          const dir = path.dirname(args.filename);
          if (dir && !fs.existsSync(dir)) {
            fs.mkdirSync(dir, { recursive: true });
          }
          // 先关闭 context，让 Playwright 落盘完整 WebM，避免只生成 0 字节文件
          if (context) {
            await context.close().catch(() => {});
            context = null;
            page = null;
          }
          await video.saveAs(args.filename);
          return { content: [{ type: 'text', text: args.filename }] };
        }
        return { content: [{ type: 'text', text: 'no video' }] };
      }

      case 'browser_close': {
        if (page) { await page.close().catch(() => {}); }
        if (context) { await context.close().catch(() => {}); }
        if (browser) { await browser.close().catch(() => {}); }
        browser = null;
        context = null;
        page = null;
        return { content: [{ type: 'text', text: 'closed' }] };
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
console.error('[Playwright MCP Server] 启动成功 v1.0 | tools=9');
