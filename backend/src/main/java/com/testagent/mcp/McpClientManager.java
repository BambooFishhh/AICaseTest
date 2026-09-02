package com.testagent.mcp;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.testagent.common.ToolRetryPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * v2.6: MCP 多 Server 管理器。
 * 替代原 McpClient，支持同时管理多个 MCP Server 连接。
 * 当前管理 "llm" Server，v2.7 将新增 "playwright" Server。
 */
@Component
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();

    // LLM MCP Server 配置（向后兼容旧配置 mcp.server.*）
    @Value("${mcp.servers.llm.node-path:${mcp.server.node-path:node}}")
    private String llmNodePath;

    @Value("${mcp.servers.llm.script-path:${mcp.server.script-path:mcp-server/index.js}}")
    private String llmScriptPath;

    @Value("${llm.api-key:}")
    private String llmApiKey;

    @Value("${llm.base-url:https://open.bigmodel.cn/api/paas/v4}")
    private String llmBaseUrl;

    @Value("${llm.model:gpt-4o}")
    private String llmModel;

    @Value("${llm.embedding-model:qwen3.7-text-embedding}")
    private String llmEmbeddingModel;

    @Value("${llm.enable-thinking:false}")
    private boolean llmEnableThinking;

    // v2.7: Playwright MCP Server 配置
    @Value("${mcp.servers.playwright.node-path:node}")
    private String playwrightNodePath;

    @Value("${mcp.servers.playwright.script-path:playwright-mcp-server/index.js}")
    private String playwrightScriptPath;

    // v5.13: tools MCP Server（桥接语义检索/需求解析/状态机/AI评审/代码分析）
    @Value("${mcp.servers.tools.node-path:node}")
    private String toolsNodePath;

    @Value("${mcp.servers.tools.script-path:tools-mcp-server/index.js}")
    private String toolsScriptPath;

    @Value("${app.mcp.bridge-url:http://127.0.0.1:8000}")
    private String mcpBridgeUrl;

    @Value("${app.mcp.bridge-token:aicasetest-mcp-local}")
    private String mcpBridgeToken;

    @Value("${app.mcp.request-timeout-seconds:60}")
    private long requestTimeoutSeconds;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    // vT6: 测试环境可关闭 MCP 子进程启动
    @Value("${app.mcp.enabled:true}")
    private boolean mcpEnabled;

    @PostConstruct
    public void start() {
        if (!mcpEnabled) {
            log.info("MCP disabled (app.mcp.enabled=false), skip spawning MCP servers");
            return;
        }
        // v5.14: LLM 能力按连接拆分——vision 保留 "llm"
        // v6.0: chat/stream/embedding 已迁移到 Spring AI，不再独立拉起 llm-chat/llm-stream/llm-embedding 子进程
        Map<String, String> llmEnv = new HashMap<>();
        llmEnv.put("OPENAI_API_KEY", llmApiKey);
        llmEnv.put("OPENAI_BASE_URL", llmBaseUrl);
        llmEnv.put("OPENAI_MODEL", llmModel);
        llmEnv.put("OPENAI_EMBEDDING_MODEL", llmEmbeddingModel);
        llmEnv.put("LLM_ENABLE_THINKING", String.valueOf(llmEnableThinking));

        // 多模态/视觉识别继续使用原 "llm" 连接
        McpConnection llmConn = new McpConnection("llm", llmNodePath, llmScriptPath, null, llmEnv,
                requestTimeoutSeconds);
        llmConn.start();
        connections.put("llm", llmConn);

        // v2.7: 创建并启动 "playwright" Server
        McpConnection playwrightConn = new McpConnection("playwright",
                playwrightNodePath, playwrightScriptPath, null, new HashMap<>(), requestTimeoutSeconds);
        playwrightConn.start();
        connections.put("playwright", playwrightConn);

        // v5.13: 创建并启动 "tools" Server
        Map<String, String> toolsEnv = new HashMap<>();
        toolsEnv.put("MCP_BRIDGE_URL", mcpBridgeUrl);
        toolsEnv.put("MCP_BRIDGE_TOKEN", mcpBridgeToken);
        McpConnection toolsConn = new McpConnection("tools", toolsNodePath, toolsScriptPath, null, toolsEnv,
                requestTimeoutSeconds);
        toolsConn.start();
        connections.put("tools", toolsConn);

        log.info("McpClientManager 启动完成，已注册 {} 个 Server", connections.size());
    }

    /**
     * 路由调用到指定 Server。
     *
     * @param serverName Server 名称（如 "llm"、"playwright"）
     * @param toolName   工具名称
     * @param args       工具参数
     * @return 工具返回的文本内容
     */
    public String callTool(String serverName, String toolName, Map<String, Object> args) throws Exception {
        return invokeTool(serverName, toolName, args, false, null).getText();
    }

    /**
     * v5.14: 调用工具并返回文本 + usage 元数据。
     */
    public McpToolResult callToolWithMeta(String serverName, String toolName, Map<String, Object> args) throws Exception {
        return invokeTool(serverName, toolName, args, false, null);
    }

    /**
     * v3.7: 流式调用工具。路由到 McpConnection.callToolStreaming。
     */
    public String callToolStreaming(String serverName, String toolName,
                                    Map<String, Object> args,
                                    Consumer<String> chunkConsumer) throws Exception {
        return invokeTool(serverName, toolName, args, true, chunkConsumer).getText();
    }

    /**
     * v5.14: 流式调用工具并返回文本 + usage 元数据。
     */
    public McpToolResult callToolStreamingWithMeta(String serverName, String toolName,
                                                   Map<String, Object> args,
                                                   Consumer<String> chunkConsumer) throws Exception {
        return invokeTool(serverName, toolName, args, true, chunkConsumer);
    }

    private McpToolResult invokeTool(String serverName, String toolName, Map<String, Object> args,
                                     boolean streaming, Consumer<String> chunkConsumer) throws Exception {
        McpConnection conn = connections.get(serverName);
        if (conn == null) {
            throw new IllegalArgumentException("未知 MCP Server: " + serverName);
        }
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return streaming
                        ? conn.callToolStreamingWithMeta(toolName, args, chunkConsumer)
                        : conn.callToolWithMeta(toolName, args);
            } catch (Exception e) {
                last = e;
                countToolFailure(serverName, toolName, classify(e));
                boolean idempotent = ToolRetryPolicy.isIdempotentTool(serverName, toolName);
                if (attempt == 0 && idempotent && ToolRetryPolicy.isRetryable(e)) {
                    long delay = 500 + (long) (Math.random() * 300);
                    log.warn("MCP [{}] tool {} failed ({}), retry once in {}ms",
                            serverName, toolName, e.getMessage(), delay);
                    Thread.sleep(delay);
                    continue;
                }
                throw e;
            }
        }
        throw last;
    }

    private String classify(Throwable e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("请求超时") || message.contains("timeout")) {
            return "TOOL_TIMEOUT";
        }
        if (e instanceof java.io.IOException || message.contains("未启动") || message.contains("已停止")) {
            return "TOOL_UNAVAILABLE";
        }
        return "TOOL_ERROR";
    }

    private void countToolFailure(String serverName, String toolName, String errorCode) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("aicasetest.tool.failures_total",
                        "server", serverName, "tool", toolName, "error_code", errorCode)
                .increment();
    }

    /**
     * 检查指定 Server 是否可用。
     */
    public boolean isAvailable(String serverName) {
        McpConnection conn = connections.get(serverName);
        return conn != null && conn.isAvailable();
    }

    /**
     * 检查是否至少有一个 Server 可用。
     */
    public boolean isAnyAvailable() {
        return connections.values().stream().anyMatch(McpConnection::isAvailable);
    }

    /**
     * v5.14: 中断指定连接的当前流式请求（用于取消生成）。
     */
    public void cancelStreaming(String serverName) {
        McpConnection conn = connections.get(serverName);
        if (conn != null) {
            conn.cancelActiveStreaming();
        }
    }

    @PreDestroy
    public void stopAll() {
        connections.values().forEach(McpConnection::stop);
        connections.clear();
        log.info("McpClientManager 已关闭所有 Server");
    }
}
