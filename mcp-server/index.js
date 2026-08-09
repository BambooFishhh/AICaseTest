/**
 * AICaseTest MCP Server
 * 独立多模态视觉识别服务，通过 MCP 协议（stdio）提供工具。
 *
 * 暴露工具：
 *   multimodal_element_locate(image_path, element_desc) → JSON
 *
 * 环境变量：
 *   OPENAI_API_KEY  — LLM API Key
 *   OPENAI_BASE_URL — LLM API 地址（默认 https://api.xiaomimimo.com/v1）
 *   OPENAI_MODEL    — 模型名称（默认 gpt-4o）
 */

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';
import OpenAI from 'openai';
import fs from 'fs';

const apiKey = process.env.OPENAI_API_KEY || '';
const baseUrl = process.env.OPENAI_BASE_URL || 'https://api.xiaomimimo.com/v1';
const model = process.env.OPENAI_MODEL || 'gpt-4o';

const client = new OpenAI({ apiKey, baseURL: baseUrl });

const SYSTEM_PROMPT = `你是页面控件视觉识别专家。请看截图，找到用户描述的控件位置。
返回纯 JSON（不要 markdown 代码块），格式固定：
{"found": true/false, "bbox": [x1,y1,x2,y2], "click_center": {"x": 0, "y": 0}, "element_text": "", "confidence": 0.0}
- found: 是否找到目标控件
- bbox: 控件边界框坐标 [左上x, 左上y, 右下x, 右下y]
- click_center: 点击中心坐标
- element_text: 控件上的文字
- confidence: 置信度 0-1
如果找不到目标控件，found 设为 false，其他字段留空或为 0。`;

const server = new Server(
  { name: 'aicasetest-mcp-server', version: '1.0.0' },
  { capabilities: { tools: {} } }
);

// 列出工具
server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: 'multimodal_element_locate',
      description: '多模态视觉识别：接收截图路径+自然语言描述，返回控件位置JSON',
      inputSchema: {
        type: 'object',
        properties: {
          image_path: {
            type: 'string',
            description: '截图文件路径',
          },
          element_desc: {
            type: 'string',
            description: '元素自然语言描述，如"找到页面登录按钮"',
          },
        },
        required: ['image_path', 'element_desc'],
      },
    },
  ],
}));

// 调用工具
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  if (name !== 'multimodal_element_locate') {
    throw new Error(`未知工具: ${name}`);
  }

  const { image_path, element_desc } = args;

  try {
    // 1. 读取图片 → base64
    const imageBuffer = fs.readFileSync(image_path);
    const imageBase64 = imageBuffer.toString('base64');

    // 2. 调用多模态 LLM
    const response = await client.chat.completions.create({
      model: model,
      temperature: 0.1,
      max_tokens: 1024,
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        {
          role: 'user',
          content: [
            { type: 'text', text: `请在截图中找到以下控件: ${element_desc}` },
            {
              type: 'image_url',
              image_url: { url: `data:image/png;base64,${imageBase64}` },
            },
          ],
        },
      ],
    });

    const resultText = response.choices[0]?.message?.content || '{"found":false}';

    return {
      content: [{ type: 'text', text: resultText }],
    };
  } catch (error) {
    const errorResult = JSON.stringify({
      found: false,
      error: `MCP Server 异常: ${error.message}`,
    });
    return {
      content: [{ type: 'text', text: errorResult }],
      isError: true,
    };
  }
});

// 启动 server（stdio 传输）
const transport = new StdioServerTransport();
await server.connect(transport);

// 向 stderr 输出启动日志（不能用 stdout，stdout 是 MCP 通信通道）
console.error(`[MCP Server] 启动成功 | model=${model} | baseUrl=${baseUrl}`);
