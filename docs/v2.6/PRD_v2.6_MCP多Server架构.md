# PRD v2.6 — MCP Client 多 Server 架构

> 版本：v2.6 | 主题：MCP Client 多 Server 架构 | 基线：v2.5

## 一、迭代背景与痛点分析

### 背景

v2.2 引入 MCP 协议，v2.3 将所有 LLM 调用迁移到独立 MCP Server。当前架构中，`McpClient` 是单例 `@Component`，仅管理一个 Node.js MCP Server 子进程（LLM MCP Server），通过 stdio JSON-RPC 2.0 通信。

v2.7-v2.9 规划将浏览器引擎从 Selenium 迁移到 Playwright MCP，需要接入第二个独立的 MCP Server（`@playwright/record-mcp`）。当前 `McpClient` 不支持同时管理多个 MCP Server 连接。

### 痛点

| 痛点 | 影响 |
|------|------|
| McpClient 硬编码单 Server 连接 | 无法接入 Playwright MCP，阻塞 v2.7 迁移 |
| 配置 `mcp.server.*` 只支持单个 Server | 新增 Server 需改代码 |
| `callTool(toolName, args)` 无 Server 路由 | 不同 Server 可能有同名工具，无法区分 |
| 连接逻辑与业务耦合 | McpClient 既管进程生命周期又管 JSON-RPC 通信，职责过重 |

## 二、范围

### In Scope（本次做）

1. 抽取 `McpConnection` 类，封装单个 MCP Server 的进程管理 + JSON-RPC 通信
2. 新建 `McpClientManager`，管理 `Map<String, McpConnection>` 多 Server 连接
3. 配置文件从 `mcp.server.*` 改为 `mcp.servers.{name}.*` 多 Server 格式
4. `LlmService` 适配新接口 `callTool(serverName, toolName, args)`
5. `SettingsService` 适配新接口

### Out of Scope（本次不做）

- 接入 Playwright MCP Server（v2.7）
- Fork @playwright/record-mcp 加截图/坐标点击工具（v2.7）
- PlaywrightRecordSkill 实现（v2.7）
- 执行链路切换（v2.8）
- 前端改动（v2.6 无前端改动）
- 移除 Selenium 依赖（v2.9）

## 三、功能详情

### 3.1 McpConnection — 单 Server 连接封装

从现有 `McpClient` 中抽取连接级逻辑：

```
McpConnection
├── 字段：name, process, stdin, stdout, initialized, requestId
├── start()           — 启动子进程 + MCP 握手
├── callTool(name, args) — 调用工具（synchronized 防并发）
├── isAvailable()     — 连接状态
├── stop()            — 关闭子进程
└── sendRequest() / sendNotification() — JSON-RPC 内部方法
```

不是 Spring Bean，由 `McpClientManager` 在 `@PostConstruct` 时根据配置创建。

### 3.2 McpClientManager — 多 Server 管理器

```
McpClientManager (@Component)
├── Map<String, McpConnection> connections
├── @PostConstruct start()  — 遍历配置，启动所有 Server
├── callTool(serverName, toolName, args)  — 路由到指定 Server
├── isAvailable(serverName)  — 指定 Server 状态
├── isAnyAvailable()         — 至少一个可用
└── @PreDestroy stopAll()    — 关闭所有 Server
```

### 3.3 配置格式

从：
```yaml
mcp:
  server:
    node-path: node
    script-path: mcp-server/index.js
```

改为：
```yaml
mcp:
  servers:
    llm:
      node-path: ${MCP_NODE_PATH:node}
      script-path: mcp-server/index.js
      env:
        OPENAI_API_KEY: ${LLM_API_KEY:}
        OPENAI_BASE_URL: ${LLM_BASE_URL:https://api.xiaomimimo.com/v1}
        OPENAI_MODEL: ${LLM_MODEL:gpt-4o}
    # v2.7 将启用：
    # playwright:
    #   node-path: npx
    #   script-path: '@playwright/record-mcp@latest'
    #   args: ['--headless', '--record']
```

### 3.4 调用方适配

| 调用方 | 原调用 | 新调用 |
|--------|--------|--------|
| LlmService.chat | `mcpClient.callTool("llm_chat", args)` | `mcpClientManager.callTool("llm", "llm_chat", args)` |
| LlmService.chatWithImage | `mcpClient.callTool("llm_chat_with_image", args)` | `mcpClientManager.callTool("llm", "llm_chat_with_image", args)` |
| LlmService.isConfigured | `mcpClient.isAvailable()` | `mcpClientManager.isAvailable("llm")` |
| SettingsService.testConnection | `mcpClient.isAvailable()` | `mcpClientManager.isAvailable("llm")` |

## 四、验收标准

1. `mvn compile` 编译通过
2. 后端启动后，`llm` MCP Server 正常连接（日志可见握手成功）
3. `GET /api/settings` 返回 `mcpAvailable: true`
4. `POST /api/settings/test-llm` LLM 测试调用成功
5. 配置文件支持多 Server 定义（即使 Playwright Server 暂未启用，配置结构已就绪）

## 五、风险与缓解

| 风险 | 缓解 |
|------|------|
| 配置格式不兼容旧版 | `mcp.server.*` → `mcp.servers.llm.*`，在 McpClientManager 中做向后兼容读取 |
| stdio 并发调用乱序 | `McpConnection.callTool` 加 `synchronized`，同一 Server 串行调用 |
| Server 启动失败影响全局 | 单个 Server 启动失败只标记该 Server 不可用，不影响其他 Server |

## 六、交付物清单

- [ ] `McpConnection.java` — 单 Server 连接封装
- [ ] `McpClientManager.java` — 多 Server 管理器（替代 McpClient）
- [ ] `McpClient.java` — 删除或改为空壳
- [ ] `application.yml` — 配置格式迁移
- [ ] `LlmService.java` — 适配新接口
- [ ] `SettingsService.java` — 适配新接口
- [ ] PRD + 后端技术评审 + 前端技术评审（无前端改动说明）
- [ ] CHANGELOG + README 更新
