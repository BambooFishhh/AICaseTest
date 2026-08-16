package com.testagent.mcp;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @Value("${llm.base-url:https://api.xiaomimimo.com/v1}")
    private String llmBaseUrl;

    @Value("${llm.model:gpt-4o}")
    private String llmModel;

    // v2.7: Playwright MCP Server 配置
    @Value("${mcp.servers.playwright.node-path:node}")
    private String playwrightNodePath;

    @Value("${mcp.servers.playwright.script-path:playwright-mcp-server/index.js}")
    private String playwrightScriptPath;

    // vT6: 测试环境可关闭 MCP 子进程启动
    @Value("${app.mcp.enabled:true}")
    private boolean mcpEnabled;

    @PostConstruct
    public void start() {
        if (!mcpEnabled) {
            log.info("MCP disabled (app.mcp.enabled=false), skip spawning MCP servers");
            return;
        }
        // 创建并启动 "llm" Server
        Map<String, String> llmEnv = new HashMap<>();
        llmEnv.put("OPENAI_API_KEY", llmApiKey);
        llmEnv.put("OPENAI_BASE_URL", llmBaseUrl);
        llmEnv.put("OPENAI_MODEL", llmModel);

        McpConnection llmConn = new McpConnection("llm", llmNodePath, llmScriptPath, null, llmEnv);
        llmConn.start();
        connections.put("llm", llmConn);

        // v2.7: 创建并启动 "playwright" Server
        McpConnection playwrightConn = new McpConnection("playwright",
                playwrightNodePath, playwrightScriptPath, null, new HashMap<>());
        playwrightConn.start();
        connections.put("playwright", playwrightConn);

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
        McpConnection conn = connections.get(serverName);
        if (conn == null) {
            throw new IllegalArgumentException("未知 MCP Server: " + serverName);
        }
        return conn.callTool(toolName, args);
    }

    /**
     * v5.14: 调用工具并返回文本 + usage 元数据。
     */
    public McpToolResult callToolWithMeta(String serverName, String toolName, Map<String, Object> args) throws Exception {
        McpConnection conn = connections.get(serverName);
        if (conn == null) {
            throw new IllegalArgumentException("未知 MCP Server: " + serverName);
        }
        return conn.callToolWithMeta(toolName, args);
    }

    /**
     * v3.7: 流式调用工具。路由到 McpConnection.callToolStreaming。
     */
    public String callToolStreaming(String serverName, String toolName,
                                    Map<String, Object> args,
                                    Consumer<String> chunkConsumer) throws Exception {
        McpConnection conn = connections.get(serverName);
        if (conn == null) {
            throw new IllegalArgumentException("未知 MCP Server: " + serverName);
        }
        return conn.callToolStreaming(toolName, args, chunkConsumer);
    }

    /**
     * v5.14: 流式调用工具并返回文本 + usage 元数据。
     */
    public McpToolResult callToolStreamingWithMeta(String serverName, String toolName,
                                                   Map<String, Object> args,
                                                   Consumer<String> chunkConsumer) throws Exception {
        McpConnection conn = connections.get(serverName);
        if (conn == null) {
            throw new IllegalArgumentException("未知 MCP Server: " + serverName);
        }
        return conn.callToolStreamingWithMeta(toolName, args, chunkConsumer);
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

    @PreDestroy
    public void stopAll() {
        connections.values().forEach(McpConnection::stop);
        connections.clear();
        log.info("McpClientManager 已关闭所有 Server");
    }
}
