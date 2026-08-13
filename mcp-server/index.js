/**
 * AICaseTest MCP Server
 * 独立 LLM 服务，通过 MCP 协议（stdio）提供工具。
 *
 * v2.2: multimodal_element_locate（多模态视觉识别）
 * v2.3: llm_chat（文本对话）、llm_chat_with_image（多模态对话）
 *
 * 环境变量：
 *   OPENAI_API_KEY  — LLM API Key
 *   OPENAI_BASE_URL — LLM API 地址（默认 https://api.xiaomimimo.com/v1）
 *   OPENAI_MODEL    — 模型名称（默认 gpt-4o）
 *   LLM_API_KEY / LLM_BASE_URL / LLM_MODEL — 兼容后端容器注入的 LLM_* 变量（v4.4 修复）
 */

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';
import OpenAI from 'openai';
import fs from 'fs';

const apiKey = process.env.OPENAI_API_KEY || process.env.LLM_API_KEY || '';
const baseUrl = process.env.OPENAI_BASE_URL || process.env.LLM_BASE_URL || 'https://api.xiaomimimo.com/v1';
const model = process.env.OPENAI_MODEL || process.env.LLM_MODEL || 'gpt-4o';

const client = new OpenAI({ apiKey, baseURL: baseUrl });

const LOCATE_SYSTEM_PROMPT = `你是页面控件视觉识别专家。请看截图，找到用户描述的控件位置。
返回纯 JSON（不要 markdown 代码块），格式固定：
{"found": true/false, "bbox": [x1,y1,x2,y2], "click_center": {"x": 0, "y": 0}, "element_text": "", "confidence": 0.0}
- found: 是否找到目标控件
- bbox: 控件边界框坐标 [左上x, 左上y, 右下x, 右下y]
- click_center: 点击中心坐标
- element_text: 控件上的文字
- confidence: 置信度 0-1
如果找不到目标控件，found 设为 false，其他字段留空或为 0。`;

// v3.7: StreamingServer 子类，暴露 protected notification() 用于流式 chunk 推送
class StreamingServer extends Server {
  async sendChunkNotification(text, index) {
    await this.notification({
      method: 'notifications/llm_chunk',
      params: { text, index },
    });
  }
}

const server = new StreamingServer(
  { name: 'aicasetest-mcp-server', version: '1.2.0' },
  { capabilities: { tools: {} } }
);

// 列出工具
server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: 'llm_chat',
      description: 'LLM 文本对话：system_prompt + user_prompt → 文本响应',
      inputSchema: {
        type: 'object',
        properties: {
          system_prompt: { type: 'string', description: '系统提示词' },
          user_prompt: { type: 'string', description: '用户输入' },
          temperature: { type: 'number', description: '温度参数（默认 0.7）', default: 0.7 },
          stream: { type: 'boolean', description: '是否启用流式输出（v3.7）', default: false },
        },
        required: ['system_prompt', 'user_prompt'],
      },
    },
    {
      name: 'llm_chat_with_image',
      description: 'LLM 多模态对话：system_prompt + user_text + image_base64 → 文本响应',
      inputSchema: {
        type: 'object',
        properties: {
          system_prompt: { type: 'string', description: '系统提示词' },
          user_text: { type: 'string', description: '用户文本输入' },
          image_base64: { type: 'string', description: '图片 base64 编码（不含 data: 前缀）' },
        },
        required: ['system_prompt', 'user_text', 'image_base64'],
      },
    },
    {
      name: 'multimodal_element_locate',
      description: '多模态视觉识别：截图路径+自然语言描述→控件位置JSON',
      inputSchema: {
        type: 'object',
        properties: {
          image_path: { type: 'string', description: '截图文件路径' },
          element_desc: { type: 'string', description: '元素自然语言描述' },
        },
        required: ['image_path', 'element_desc'],
      },
    },
  ],
}));

// 调用工具
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  try {
    switch (name) {
      case 'llm_chat': {
        const { system_prompt, user_prompt, temperature = 0.7, stream = false } = args;
        // v3.7: 流式模式——逐块推送 notification + 累积完整文本
        if (stream) {
          let fullText = '';
          const completion = await client.chat.completions.create({
            model,
            temperature: parseFloat(temperature),
            max_tokens: 8192,
            stream: true,
            messages: [
              { role: 'system', content: system_prompt },
              { role: 'user', content: user_prompt },
            ],
          });
          let chunkIndex = 0;
          for await (const chunk of completion) {
            const delta = chunk.choices[0]?.delta?.content || '';
            if (delta) {
              fullText += delta;
              await server.sendChunkNotification(delta, chunkIndex++);
            }
          }
          return { content: [{ type: 'text', text: fullText }] };
        }
        // 非流式：原有逻辑
        const response = await client.chat.completions.create({
          model,
          temperature: parseFloat(temperature),
          max_tokens: 8192,
          messages: [
            { role: 'system', content: system_prompt },
            { role: 'user', content: user_prompt },
          ],
        });
        const text = response.choices[0]?.message?.content || '';
        return { content: [{ type: 'text', text }] };
      }

      case 'llm_chat_with_image': {
        const { system_prompt, user_text, image_base64 } = args;
        const response = await client.chat.completions.create({
          model,
          temperature: 0.1,
          max_tokens: 4096,
          messages: [
            { role: 'system', content: system_prompt },
            {
              role: 'user',
              content: [
                { type: 'text', text: user_text },
                { type: 'image_url', image_url: { url: `data:image/png;base64,${image_base64}` } },
              ],
            },
          ],
        });
        const text = response.choices[0]?.message?.content || '';
        return { content: [{ type: 'text', text }] };
      }

      case 'multimodal_element_locate': {
        const { image_path, element_desc } = args;
        const imageBuffer = fs.readFileSync(image_path);
        const imageBase64 = imageBuffer.toString('base64');
        const response = await client.chat.completions.create({
          model,
          temperature: 0.1,
          max_tokens: 1024,
          messages: [
            { role: 'system', content: LOCATE_SYSTEM_PROMPT },
            {
              role: 'user',
              content: [
                { type: 'text', text: `请在截图中找到以下控件: ${element_desc}` },
                { type: 'image_url', image_url: { url: `data:image/png;base64,${imageBase64}` } },
              ],
            },
          ],
        });
        const resultText = response.choices[0]?.message?.content || '{"found":false}';
        return { content: [{ type: 'text', text: resultText }] };
      }

      default:
        throw new Error(`未知工具: ${name}`);
    }
  } catch (error) {
    return {
      content: [{ type: 'text', text: `MCP Server 错误: ${error.message}` }],
      isError: true,
    };
  }
});

// 启动 server（stdio 传输）
const transport = new StdioServerTransport();
await server.connect(transport);

console.error(`[MCP Server] 启动成功 v1.2 | model=${model} | baseUrl=${baseUrl} | tools=3 | streaming=true`);
