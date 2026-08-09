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
        await page.goto(args.url, { waitUntil: 'load', timeout: 30000 });
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
        await page.mouse.click(args.x, args.y);
        return { content: [{ type: 'text', text: `clicked (${args.x},${args.y})` }] };
      }

      case 'browser_dom_click': {
        await page.click(args.selector, { timeout: 10000 });
        return { content: [{ type: 'text', text: `clicked ${args.selector}` }] };
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
        const video = page.video();
        if (video) {
          const dir = path.dirname(args.filename);
          if (dir && !fs.existsSync(dir)) {
            fs.mkdirSync(dir, { recursive: true });
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
