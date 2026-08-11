# PRD v3.7 — 真正的 LLM 流式输出

**日期**: 2026-08-11
**基线**: v3.6
**主题**: 将"伪流式"升级为"真流式"——LLM 逐 token 生成 → MCP Server 逐块推送 → Java 逐行解析 → 增量 JSON 解析 → SSE 逐条推送，首条用例出现时间从 40~120 秒降至 ~5 秒

---

## 1. 背景与痛点

### 1.1 现状（v3.2~v3.6 的"伪流式"）

当前用例生成的数据流：

```
用户点击"生成用例"
  → 后端 SSE 端点建立连接
  → OrchestratorAgent 调用 TestGeneratorAgent
  → TestGeneratorAgent 调用 LlmService.chat()
  → LlmService 通过 MCP 协议调用 MCP Server 的 llm_chat 工具
  → MCP Server 调用 OpenAI API（stream: false，阻塞等待完整响应）  ← 40~120 秒
  → MCP Server 返回完整 JSON
  → McpConnection.sendRequest 单行 readLine() 阻塞读取
  → LlmService.chat() 返回完整响应
  → TestGeneratorAgent.parseTestCases() 逐条解析，caseCb 回调
  → TestCaseService 通过 SSE 逐条推送用例
```

**核心问题**：LLM 调用是阻塞的，用户在前 40~120 秒只看到"已收到 0 条"，然后所有用例瞬间出现。虽然技术上逐条 SSE 推送，但用户感知不到"正在生成"。

### 1.2 痛点量化

| 指标 | 当前（伪流式） | 目标（真流式） |
|------|---------------|---------------|
| 首条用例出现时间 | 40~120 秒（LLM 完整响应后） | ~5 秒（首个完整用例对象解析后） |
| 用户可见进度反馈 | 仅"正在生成..."文字 | 用例逐条出现 + 生成文本流 |
| LLM 响应中断风险 | 高（长等待超时） | 低（逐块接收，已接收部分不丢失） |

### 1.3 根因分析

1. **MCP Server** (`mcp-server/index.js` L96-106)：`client.chat.completions.create()` 未传 `stream: true`，阻塞等待完整响应
2. **McpConnection** (`McpConnection.java` L173-179)：`sendRequest` 单次 `stdout.readLine()` 阻塞读取，无法处理流式通知
3. **LlmService** (`LlmService.java` L51-83)：`chat()` 方法同步阻塞，无流式回调
4. **TestGeneratorAgent** (`TestGeneratorAgent.java` L462, L528)：`llmService.chat()` 返回后才 `parseTestCases()`，无法增量解析

---

## 2. 范围

### 2.1 In Scope（本迭代实现）

| # | 改动点 | 说明 |
|---|--------|------|
| 1 | MCP Server `llm_chat` 流式模式 | 新增 `stream` 参数，启用后 OpenAI `stream: true`，逐块通过 JSON-RPC notification 推送 |
| 2 | McpConnection `callToolStreaming` | 新增流式调用方法，循环读取 stdout 行，dispatch chunk 通知，等待匹配响应 |
| 3 | McpClientManager `callToolStreaming` | 路由到 McpConnection.callToolStreaming |
| 4 | LlmService `chatStreaming` | 新增流式 chat 方法，接收 chunk 回调 |
| 5 | TestGeneratorAgent 增量 JSON 解析 | 新增 `StreamingTestCaseParser`，积累流式文本，检测完整用例对象后立即 caseCb 回调 |
| 6 | 前端流式进度优化 | 显示"正在接收 LLM 流式响应..." + 已接收字符数 |

### 2.2 Out of Scope（不做）

- 不修改非流式 `chat()` / `callTool()` 方法（向后兼容）
- 不修改 `llm_chat_with_image` / `multimodal_element_locate`（多模态暂不流式）
- 不修改 SSE 协议本身（前端 EventSource 不变）
- 不修改规则回退生成路径（非 LLM 调用无需流式）

---

## 3. 技术方案

### 3.1 数据流（改造后）

```
用户点击"生成用例"
  → SSE 端点建立连接
  → TestGeneratorAgent 调用 LlmService.chatStreaming()
  → LlmService 通过 McpClientManager.callToolStreaming() 调用 llm_chat (stream=true)
  → McpConnection.callToolStreaming() 发送 JSON-RPC 请求
  → MCP Server 调用 OpenAI API (stream: true)
  → OpenAI 逐 token 返回 chunk
  → MCP Server 逐块发送 JSON-RPC notification: {"method":"notifications/llm_chunk","params":{"text":"..."}}
  → McpConnection 循环 readLine()，收到 notification → 调用 chunkConsumer
  → LlmService.chatStreaming 的 chunkConsumer 调用 TestGeneratorAgent 的 parser.append(text)
  → StreamingTestCaseParser 检测到完整用例对象 → caseCb.onCase(tc)
  → SSE 推送 case 事件到前端
  → MCP Server 收到 OpenAI 完成 → 返回 JSON-RPC response（完整文本）
  → McpConnection 收到 response → callToolStreaming 返回
  → LlmService.chatStreaming 返回完整文本
  → TestGeneratorAgent 用完整文本做最终 parseTestCases（兜底，确保不遗漏）
```

### 3.2 MCP Server 改造

**文件**: `mcp-server/index.js`

新增 `StreamingServer` 子类，暴露 `protected notification()` 方法：

```javascript
class StreamingServer extends Server {
  async sendChunkNotification(text, index) {
    await this.notification({
      method: "notifications/llm_chunk",
      params: { text, index }
    });
  }
}
```

`llm_chat` 工具新增 `stream` 参数：

```javascript
case 'llm_chat': {
  const { system_prompt, user_prompt, temperature = 0.7, stream = false } = args;
  if (stream) {
    let fullText = '';
    const completion = await client.chat.completions.create({
      model, temperature: parseFloat(temperature), max_tokens: 8192, stream: true,
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
  // 非流式：原有逻辑不变
  const response = await client.chat.completions.create({ ... stream: false ... });
  ...
}
```

### 3.3 McpConnection 流式调用

**文件**: `McpConnection.java`

新增 `callToolStreaming` 方法：

```java
public synchronized String callToolStreaming(
        String toolName, Map<String, Object> args,
        Consumer<String> chunkConsumer) throws Exception {
    // ... 同 callTool 的前置检查 ...

    int id = requestId.incrementAndGet();
    // 构建并发送 tools/call 请求（同 sendRequest 但不调用它）

    // 循环读取 stdout 行
    while (true) {
        String line = stdout.readLine();
        if (line == null) throw new IOException("stdout 已关闭");

        JsonNode msg = objectMapper.readTree(line);

        // 是 notification（有 method 无 id）→ dispatch
        if (msg.has("method") && !msg.has("id")) {
            String method = msg.path("method").asText();
            if ("notifications/llm_chunk".equals(method) && chunkConsumer != null) {
                String chunkText = msg.path("params").path("text").asText("");
                chunkConsumer.accept(chunkText);
            }
            // 其他 notification 忽略
            continue;
        }

        // 是 response（有 id）→ 匹配则返回
        if (msg.has("id") && msg.path("id").asInt() == id) {
            // 处理 error / 提取 content[0].text（同 callTool）
            return extractContentText(msg);
        }
        // id 不匹配 → 忽略（不应发生在 synchronized 模式下）
    }
}
```

### 3.4 StreamingTestCaseParser 增量解析

**文件**: `TestGeneratorAgent.java` 内部类

```java
/**
 * 流式 JSON 数组解析器：积累文本，检测完整用例对象后回调。
 * 状态机跟踪：数组外 → 数组内(brace depth=0) → 对象内(depth>0)
 */
class StreamingTestCaseParser {
    private final StringBuilder buffer = new StringBuilder();
    private final CaseCallback caseCb;
    private int arrayStart = -1;    // 数组 [ 的位置
    private int objStart = -1;      // 当前对象 { 的位置
    private int braceDepth = 0;     // 花括号深度
    private boolean inString = false; // 是否在字符串内
    private boolean escaped = false;  // 前一个字符是否为 \
    private int parsedCount = 0;

    void append(String chunk) {
        buffer.append(chunk);
        scan();
    }

    private void scan() {
        // 从上次扫描到的位置继续扫描新追加的字符
        // 跟踪 inString / escaped / braceDepth
        // 当检测到完整对象（braceDepth 从 1→0）时：
        //   提取对象 JSON → parseSingleTestCase → caseCb.onCase
    }

    List<TestCase> finalizeParse() {
        // 兜底：用完整 buffer 重新 parseTestCases
    }
}
```

**增量解析状态机**：

```
状态: SEARCHING_ARRAY (寻找数组开始 [)
  → 遇到 [ → 状态: IN_ARRAY (数组内，等待对象开始)

状态: IN_ARRAY (数组内，braceDepth=0)
  → 遇到 { → objStart=当前位置, braceDepth=1, 状态: IN_OBJECT
  → 遇到 ] → 结束

状态: IN_OBJECT (对象内，braceDepth>0)
  → 在字符串内: 遇到 " 且非 escaped → inString=true
  → 在字符串内: 遇到 " 且非 escaped → inString=false
  → 在字符串外: 遇到 { → braceDepth++
  → 在字符串外: 遇到 } → braceDepth--
  → braceDepth == 0 → 完整对象！提取 buffer[objStart..当前位置] → parse → caseCb
  → 状态回 IN_ARRAY
```

### 3.5 LlmService.chatStreaming

```java
public String chatStreaming(String systemPrompt, String userPrompt,
                            double temperature, Consumer<String> chunkConsumer) {
    // 同 chat() 的重试逻辑
    // 调用 mcpClientManager.callToolStreaming("llm", "llm_chat", args, chunkConsumer)
    // args 新增 stream: true
}
```

### 3.6 TestGeneratorAgent 集成

`generateByLlmWithPrd` 和 `generateByLlmForStateMachine` 改用 `chatStreaming`：

```java
// before:
String response = llmService.chat(systemPrompt, userPrompt, temperature);
String json = extractJsonArray(response);
return parseTestCases(json, caseCb);

// after:
StreamingTestCaseParser parser = new StreamingTestCaseParser(caseCb);
String response = llmService.chatStreaming(systemPrompt, userPrompt, temperature, parser::append);
// 流式期间已推送检测到的用例，兜底用完整响应重新解析
String json = extractJsonArray(response);
List<TestCase> all = parseTestCases(json, null); // caseCb=null，避免重复推送
// 去重：只推送流式期间未推送的
for (TestCase tc : all) {
    if (!parser.wasParsed(tc)) {
        if (caseCb != null) caseCb.onCase(tc);
    }
}
return all;
```

---

## 4. 验收标准

| # | 标准 | 验证方式 |
|---|------|----------|
| 1 | MCP Server `llm_chat` 支持 `stream: true` 参数 | 非 `stream` 参数行为不变 |
| 2 | 流式模式下 MCP Server 逐块发送 JSON-RPC notification | 日志可见 chunk notification |
| 3 | McpConnection.callToolStreaming 正确 dispatch chunk | 单元测试/日志验证 |
| 4 | 首条用例出现时间 < 10 秒（典型场景） | 前端计时 |
| 5 | 流式期间推送的用例数 = 最终用例数（无遗漏/重复） | 对比流式推送 vs 最终解析 |
| 6 | 非 `stream` 模式的 `callTool` / `chat` 行为完全不变 | 回归测试 |
| 7 | 前端显示"正在接收 LLM 流式响应..."进度 | UI 验证 |
| 8 | 后端编译 + 前端构建通过 | mvn compile + npm run build |

---

## 5. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| MCP SDK `notification()` 是 protected，子类无法调用 | MCP Server 无法发送 chunk 通知 | 创建 `StreamingServer extends Server` 子类，子类可调用 protected 方法 |
| 增量 JSON 解析器在复杂嵌套场景下遗漏用例 | 用例数不一致 | 最终用完整响应 `parseTestCases` 兜底，流式推送的去重对比 |
| JSON-RPC notification 与 response 行顺序 | McpConnection 死循环/误读 | 循环读取 + 按 id 匹配 response，notification 按 method dispatch |
| `callToolStreaming` 占用 synchronized 锁 40~120 秒 | 阻塞其他 MCP 调用 | 与现有 `callTool` 行为一致（也阻塞），无回退 |
| OpenAI SDK `stream: true` 返回 async iterator | MCP Server 需适配 | 使用 `for await (const chunk of completion)` 语法 |
| 网络中断导致流式中断 | 已推送用例不丢失 | SSE 已推送的用例在前端已显示；后端 catch 异常后推 error 事件 |

---

## 6. 交付物清单

- [ ] `mcp-server/index.js` — StreamingServer 子类 + llm_chat stream 参数
- [ ] `McpConnection.java` — callToolStreaming 方法
- [ ] `McpClientManager.java` — callToolStreaming 路由
- [ ] `LlmService.java` — chatStreaming 方法
- [ ] `TestGeneratorAgent.java` — StreamingTestCaseParser 内部类 + 集成
- [ ] `TestCaseList.vue` — 流式进度文案优化
- [ ] `docs/v3.7/PRD_v3.7_真正LLM流式输出.md` — 本文档
- [ ] `docs/v3.7/后端技术评审_v3.7.md`
- [ ] `docs/v3.7/前端技术评审_v3.7.md`
- [ ] `docs/CHANGELOG.md` — v3.7 章节
- [ ] `README.md` — v3.7 章节
