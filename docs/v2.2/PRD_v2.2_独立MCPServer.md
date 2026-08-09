# PRD v2.2 — 独立 MCP Server

## 版本信息
- **版本**: v2.2
- **基线**: v2.1
- **日期**: 2026-08-09
- **迭代主题**: 拆分独立 MCP Server，后端改为 MCP 客户端接入

## 背景与痛点

v2.1 的 McpBridgeService 是后端进程内的 Spring Service，直接调 LlmService.chatWithImage()：
1. **耦合**：多模态识别逻辑和后端业务代码耦合在一起
2. **不可独立部署**：MCP 服务无法单独部署、单独扩展
3. **不符合设计**：用户原始设计是"Agent 通过 MCP 协议调用独立服务"
4. **难以复用**：其他系统无法通过 MCP 协议调用视觉识别能力

## 目标

将多模态视觉识别拆分为独立 MCP Server：
- **Node.js MCP Server**：使用 @modelcontextprotocol/sdk，stdio 传输，暴露 `multimodal_element_locate` 工具
- **Java MCP Client**：后端通过 MCP 协议（JSON-RPC 2.0 over stdio）调用 MCP Server
- **McpBridgeService 重构**：从直接调 LlmService 改为通过 McpClient 调用 MCP Server

## 功能需求

### F1: MCP Server（Node.js）
- 目录：`mcp-server/`
- 依赖：`@modelcontextprotocol/sdk`, `openai`
- 传输：stdio（标准 MCP 传输方式）
- 暴露工具：
  - `multimodal_element_locate(image_path, element_desc)` → JSON
    - 读取截图文件 → base64
    - 调用 OpenAI Vision API
    - 返回 `{found, bbox, click_center, element_text, confidence}`
- 配置：通过环境变量接收 OPENAI_API_KEY、OPENAI_BASE_URL、OPENAI_MODEL
- 独立 `package.json`，可独立 `npm install && node index.js` 运行

### F2: Java MCP Client
- 新建 `McpClient.java`
- 通过 ProcessBuilder 启动 Node.js MCP Server 子进程
- JSON-RPC 2.0 通信：
  1. initialize 握手
  2. tools/call 调用工具
- 生命周期管理：Spring 启动时创建，关闭时销毁

### F3: McpBridgeService 重构
- 移除对 LlmService 的直接依赖
- 改为调用 McpClient.tools/call("multimodal_element_locate", args)
- 解析 MCP 返回的 JSON-RPC 响应

### F4: 配置
- application.yml 新增 mcp.server.nodePath、mcp.server.scriptPath
- .env 新增 MCP_SERVER_NODE_PATH（可选，默认 node）

## 验收标准
1. AC1: MCP Server 可独立运行，`node index.js` 不报错
2. AC2: 后端启动时自动启动 MCP Server 子进程
3. AC3: multimodal_element_locate 调用通过 MCP 协议完成
4. AC4: 后端编译 BUILD SUCCESS；前端无改动

## 范围
- In Scope: MCP Server + Java MCP Client + McpBridgeService 重构
- Out of Scope: 其他 LLM 调用拆分（v2.3）、前端改动

## 风险
| 风险 | 对策 |
|------|------|
| Node.js 未安装 | 启动时检测，未安装则降级为直调 LlmService |
| MCP Server 崩溃 | McpClient 检测进程存活，崩溃后自动重启 |
| stdio 通信超时 | 单次调用超时 30s，超时降级 |
