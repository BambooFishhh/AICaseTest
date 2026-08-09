# PRD v2.3 — 后端 LLM 调用全量拆分到 MCP Server

## 版本信息
- **版本**: v2.3
- **基线**: v2.2
- **日期**: 2026-08-09
- **迭代主题**: 将后端所有 LLM 调用拆分到 MCP Server，LlmService 改为 MCP 客户端

## 背景与痛点

v2.2 完成了多模态视觉识别的 MCP 拆分，但后端还有大量 LLM 调用直接走 OkHttp：
1. **PrdAgent** — PRD 解析调 `llmService.chat()`
2. **TestGeneratorAgent** — 用例生成调 `llmService.chat()` x2
3. **StateMachineAgent** — 状态机推断调 `llmService.chat()`
4. **VueAnalyzer** — LLM 补充调 `llmService.chat()`
5. **ExecutionAgent** — 执行决策调 `llmService.chat()` + `chatJson()` x3
6. **SettingsService** — 连接测试调 `llmService.testConnection()`

这些调用直接访问 OpenAI API，与后端业务代码耦合。

## 目标

将所有 LLM 调用迁移到 MCP Server：
- **MCP Server 新增 2 个工具**：`llm_chat`、`llm_chat_with_image`
- **LlmService 重构**：从 OkHttp 直调改为通过 McpClient 调用 MCP Server
- **Agent 层零改动**：PrdAgent/TestGeneratorAgent 等调用 LlmService 的接口不变

## 功能需求

### F1: MCP Server 新增工具

| 工具 | 入参 | 返回 | 说明 |
|------|------|------|------|
| `llm_chat` | system_prompt, user_prompt, temperature | text | 文本对话 |
| `llm_chat_with_image` | system_prompt, user_text, image_base64 | text | 多模态对话 |
| `multimodal_element_locate` | image_path, element_desc | JSON | v2.2 已有 |

### F2: LlmService 重构

| 方法 | v2.2 实现 | v2.3 实现 |
|------|----------|----------|
| `chat()` | OkHttp → OpenAI API | McpClient.callTool("llm_chat") |
| `chatJson()` | OkHttp → OpenAI API → JSON 解析 | callTool("llm_chat") → JSON 解析 |
| `chatWithImage()` | OkHttp → OpenAI Vision API | McpClient.callTool("llm_chat_with_image") |
| `isConfigured()` | 检查 apiKey 非空 | mcpClient.isAvailable() |
| `testConnection()` | OkHttp 试调 | callTool("llm_chat") 试调 |

### F3: 不改动的文件
- PrdAgent.java — 调 `llmService.chat()`，接口不变
- TestGeneratorAgent.java — 调 `llmService.chat()`，接口不变
- StateMachineAgent.java — 调 `llmService.chat()`，接口不变
- VueAnalyzer.java — 调 `llmService.chat()`，接口不变
- ExecutionAgent.java — 调 `llmService.chat()/chatJson()`，接口不变
- SettingsService.java — 调 `llmService.testConnection()`，接口不变

## 验收标准
1. AC1: MCP Server 暴露 3 个工具（llm_chat + llm_chat_with_image + multimodal_element_locate）
2. AC2: LlmService 所有方法通过 McpClient 实现
3. AC3: 所有 Agent 代码零改动
4. AC4: 后端编译 BUILD SUCCESS

## 范围
- In Scope: MCP Server 新增工具 + LlmService 重构
- Out of Scope: 前端改动、Agent 代码改动
