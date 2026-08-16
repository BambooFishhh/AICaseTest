# PRD v5.13 — MCP 工具化与 Prompt Skill 化

> 版本：v5.13
> 基线：v5.12
> 主题：按能力分层把可复用的 Agent 能力暴露为 MCP 工具，把内嵌 Prompt 抽为 Skill 模板

## 1. 背景与痛点

当前语义检索、需求解析、状态机提取、AI 评审、代码分析增强都内嵌在 Spring Agent/Service 中，只能被主流程调用：

- 外部 LLM 客户端或自动化脚本无法直接复用这些能力
- Prompt 硬编码在 Java 类中，修改需要重新编译
- 候选能力边界清晰，但缺少统一的工具化出口

## 2. 范围

### In Scope

- 新增 `tools-mcp-server`，提供 6 个 MCP 工具
- 后端新增 `/api/mcp/*` 桥接接口，由现有 Service/Agent 提供实现
- 新增 `PromptSkillLoader`，把 Agent 内嵌 Prompt 抽到 `backend/src/main/resources/skills/`
- 接入 `McpClientManager`、`application.yml`、`docker-compose.yml`、`backend/Dockerfile`
- 更新 API 概览、CHANGELOG、README

### Out of Scope

- 不把 `TestGeneratorAgent` 抽成 MCP（依赖流式、取消、评审、落库，保留内部服务）
- 不删除现有 Agent/Service，不改变主流程行为
- 不做前端 UI 功能改动

## 3. 功能详情

### 3.1 MCP 工具

| 工具 | 输入 | 输出 | 底层能力 |
|---|---|---|---|
| `semantic_search` | projectId, query, topK | 上下文列表 | SemanticService.retrieveContexts |
| `analyze_requirement_docs` | prdDocs, contextDocs, supplementary | PrdAnalysisResult | PrdAgent.analyze |
| `extract_state_machine` | backendResult, frontendResult | StateMachine 列表 | StateMachineAgent.extract |
| `review_test_cases` | cases, coverage | 评审后的用例列表 | TestCaseReviewAgent.review |
| `analyze_backend` | sourcePath | BackendResult | SpringAnalyzer.analyze |
| `analyze_frontend` | sourcePath | FrontendResult | VueAnalyzer.analyze |

### 3.2 桥接接口

所有接口为 `POST /api/mcp/*`，请求头携带 `X-MCP-Token`，令牌来自 `app.mcp.bridge-token`。

| 接口 | 请求体 |
|---|---|
| `POST /api/mcp/semantic-search` | `{projectId, query, topK}` |
| `POST /api/mcp/analyze-requirement-docs` | `{prdDocs, contextDocs, supplementary}` |
| `POST /api/mcp/extract-state-machine` | `{backendResult, frontendResult}` |
| `POST /api/mcp/review-test-cases` | `{cases, coverage}` |
| `POST /api/mcp/analyze-backend` | `{sourcePath}` |
| `POST /api/mcp/analyze-frontend` | `{sourcePath}` |

### 3.3 Skill 模板

`backend/src/main/resources/skills/` 新增：

- `prd-analysis.md`
- `state-machine-extraction.md`
- `state-machine-frontend-enhancement.md`
- `ai-review.md`
- `test-generation-code-header.md`
- `test-generation-code-footer.md`
- `test-generation-prd-header.md`
- `test-generation-prd-footer.md`

各 Agent 通过 `PromptSkillLoader.load(name, fallback)` 加载，资源缺失时回退到代码内 fallback，保证行为不变。

## 4. 验收标准

- `mvn test` 全部通过
- `npm run build` 成功
- `node --check tools-mcp-server/index.js` 通过
- `tools-mcp-server` 启动日志显示 `tools=6`
- 带正确 `X-MCP-Token` 调用 6 个桥接接口均返回成功
- 8 个 Skill 模板文件存在且被 Agent 引用

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| MCP 桥接接口被外部调用 | 使用内部令牌校验，默认本地开发令牌，生产可覆盖 |
| Skill 文件缺失导致 Prompt 为空 | 保留代码内 fallback |
| 新增 MCP Server 增加启动复杂度 | 沿用 McpClientManager 子进程管理，app.mcp.enabled=false 可整体关闭 |
| 代码分析工具耗时较长 | 工具保持同步返回，由调用方控制超时 |

## 6. 交付物清单

- `docs/v5.13/` 三份文档
- `tools-mcp-server/`
- `backend/src/main/java/com/testagent/controller/McpBridgeController.java`
- `backend/src/main/java/com/testagent/service/PromptSkillLoader.java`
- `backend/src/main/resources/skills/*.md`
- 后端/前端构建验证
- CHANGELOG 与 README 更新
- 提交并推送 `origin/main`
