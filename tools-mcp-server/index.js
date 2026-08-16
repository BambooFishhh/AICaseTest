/**
 * AICaseTest Tools MCP Server
 * 通过 HTTP 桥接后端 /api/mcp/* 接口，暴露 6 个可复用能力：
 * semantic_search / analyze_requirement_docs / extract_state_machine
 * review_test_cases / analyze_backend / analyze_frontend
 *
 * 环境变量：
 *   MCP_BRIDGE_URL   — 后端地址，默认 http://127.0.0.1:8000
 *   MCP_BRIDGE_TOKEN — 桥接令牌，与 app.mcp.bridge-token 一致
 */

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';

const bridgeUrl = (process.env.MCP_BRIDGE_URL || 'http://127.0.0.1:8000').replace(/\/+$/, '');
const bridgeToken = process.env.MCP_BRIDGE_TOKEN || 'aicasetest-mcp-local';

const TOOL_ENDPOINTS = {
  semantic_search: 'semantic-search',
  analyze_requirement_docs: 'analyze-requirement-docs',
  extract_state_machine: 'extract-state-machine',
  review_test_cases: 'review-test-cases',
  analyze_backend: 'analyze-backend',
  analyze_frontend: 'analyze-frontend',
};

const server = new Server(
  { name: 'aicasetest-tools-mcp', version: '1.0.0' },
  { capabilities: { tools: {} } }
);

const tools = [
  {
    name: 'semantic_search',
    description: '语义检索项目上下文（Milvus），返回最相似的上下文片段',
    inputSchema: {
      type: 'object',
      properties: {
        projectId: { type: 'string' },
        query: { type: 'string' },
        topK: { type: 'integer', default: 5 },
      },
      required: ['projectId', 'query'],
    },
  },
  {
    name: 'analyze_requirement_docs',
    description: '解析 PRD/上下文文档/补充需求为结构化 PrdAnalysisResult',
    inputSchema: {
      type: 'object',
      properties: {
        prdDocs: { type: 'array', items: { type: 'object' } },
        contextDocs: { type: 'array', items: { type: 'object' } },
        supplementary: { type: 'string' },
      },
      required: [],
    },
  },
  {
    name: 'extract_state_machine',
    description: '基于后端枚举/实体与前端旁证提取状态机',
    inputSchema: {
      type: 'object',
      properties: {
        backendResult: { type: 'object' },
        frontendResult: { type: 'object' },
      },
      required: ['backendResult'],
    },
  },
  {
    name: 'review_test_cases',
    description: '对候选用例执行规则评审与 LLM 评审',
    inputSchema: {
      type: 'object',
      properties: {
        cases: { type: 'array', items: { type: 'object' } },
        coverage: { type: 'object' },
      },
      required: ['cases'],
    },
  },
  {
    name: 'analyze_backend',
    description: '分析 Spring Boot 后端源码，返回增强后的 BackendResult',
    inputSchema: {
      type: 'object',
      properties: {
        sourcePath: { type: 'string' },
      },
      required: ['sourcePath'],
    },
  },
  {
    name: 'analyze_frontend',
    description: '分析 Vue 前端源码，返回增强后的 FrontendResult',
    inputSchema: {
      type: 'object',
      properties: {
        sourcePath: { type: 'string' },
      },
      required: ['sourcePath'],
    },
  },
];

server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools }));

async function callBridge(tool, args) {
  const endpoint = TOOL_ENDPOINTS[tool];
  if (!endpoint) {
    throw new Error(`未知工具: ${tool}`);
  }
  const response = await fetch(`${bridgeUrl}/api/mcp/${endpoint}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-MCP-Token': bridgeToken,
    },
    body: JSON.stringify(args || {}),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`bridge ${tool} failed: ${response.status} ${text}`);
  }
  return text;
}

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;
  try {
    const text = await callBridge(name, args);
    return { content: [{ type: 'text', text }] };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Tools MCP 错误: ${error.message}` }],
      isError: true,
    };
  }
});

const transport = new StdioServerTransport();
await server.connect(transport);

console.error(`[Tools MCP Server] 启动成功 v1.0 | bridgeUrl=${bridgeUrl} | tools=${tools.length}`);
