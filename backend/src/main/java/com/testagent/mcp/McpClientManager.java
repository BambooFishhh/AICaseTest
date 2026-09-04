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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * v2.6: MCP 多 Server 管理器。
 * 替代原 McpClient，支持同时管理多个 MCP Server 连接。
 * 当前管理 "llm" Server，v2.7 将新增 "playwright" Server。
 *
 * v12.22(P1): playwright 多进程连接池——并发执行每路绑定一个独立 playwright 子进程，
 * 消除单 stdio 通道/单浏览器进程在真实并发下的资源争抢与 60s 请求超时。
 * - 连接命名 playwright-0..N-1，路由按调用参数 session_id 稳定亲和（同一执行始终命中同一进程）。
 * - 外部调用方仍统一以 serverName="playwright" 发起（PlaywrightRecordSkill），
 *   由本类内部解析到池内具体连接，调用方无感知、无需改动。
 * - 兼容池大小=1（缺省）：仅起单连接 "playwright"，行为与旧版完全一致。
 */
@Component
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();

    // v12.22(P1): playwright 连接池实际子进程集合（playwright-0..N-1）
    private final List<McpConnection> playwrightPool = new ArrayList<>();
    // v12.22(P1): session_id -> 槽位下标。同执行的所有浏览器操作经此稳定路由到同一进程
    private final Map<String, Integer> playwrightSlotOfSession = new ConcurrentHashMap<>();
    // v12.22(P1): 槽位轮转分配器（首个 browser_launch 时决定归属）
    private final AtomicInteger playwrightSlotCounter = new AtomicInteger(0);

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

    // v12.22(P1): playwright 多进程连接池大小。缺省 1=旧版单进程。4 路并发建议=4。
    @Value("${app.mcp.playwright-pool-size:1}")
    private int playwrightPoolSize;

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
        // v12.22(P1): 多进程连接池——按 playwrightPoolSize 起多个独立子进程，每路执行专属一个。
        //   池大小<=1 时退化为旧版单连接，连接名保持 "playwright"（兼容旧引用/日志语义）。
        int pool = Math.max(1, playwrightPoolSize);
        playwrightSlotOfSession.clear();
        playwrightPool.clear();
        if (pool <= 1) {
            McpConnection single = new McpConnection("playwright",
                    playwrightNodePath, playwrightScriptPath, null, new HashMap<>(), requestTimeoutSeconds);
            single.start();
            connections.put("playwright", single);
            playwrightPool.add(single);
            log.info("playwright MCP 单进程模式启动完成（playwright-pool-size=1）");
        } else {
            for (int i = 0; i < pool; i++) {
                McpConnection conn = new McpConnection("playwright-" + i,
                        playwrightNodePath, playwrightScriptPath, null, new HashMap<>(), requestTimeoutSeconds);
                conn.start();
                connections.put("playwright-" + i, conn);
                playwrightPool.add(conn);
            }
            log.info("playwright MCP 连接池启动完成：{} 个独立子进程（playwright-0..{}）", pool, pool - 1);
        }

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
        McpConnection conn = resolveConnection(serverName, args);
        if (conn == null) {
            throw new IllegalArgumentException("未知 MCP Server: " + serverName);
        }
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                McpToolResult r = streaming
                        ? conn.callToolStreamingWithMeta(toolName, args, chunkConsumer)
                        : conn.callToolWithMeta(toolName, args);
                // v12.22(P1): 会话终结类工具成功后释放槽位亲和，避免 affinity 表随执行量无限增长
                if ("playwright".equals(serverName) && playwrightPool.size() > 1
                        && ("browser_close".equals(toolName) || "browser_video_save".equals(toolName))) {
                    String sid = sessionIdOf(args);
                    if (sid != null) {
                        playwrightSlotOfSession.remove(sid);
                    }
                }
                return r;
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

    /**
     * v12.22(P1): 解析 serverName 对应的实际连接。
     * playwright 为连接池时：按调用参数 session_id 稳定亲和路由——同一执行（session_id 相同）
     * 的所有浏览器操作始终落到同一子进程，保证其浏览器会话生命周期内不跨进程漂移。
     * session 首次出现（browser_launch）时轮转分配槽位；之后同 session 复用该槽位。
     * serverName 非 playwright（llm/tools）时返回 connections 直连，与旧版一致。
     */
    private McpConnection resolveConnection(String serverName, Map<String, Object> args) {
        if (!"playwright".equals(serverName)) {
            return connections.get(serverName);
        }
        if (playwrightPool.size() <= 1) {
            // 单进程模式：直接返回唯一连接（兼容旧版 "playwright" 键）
            McpConnection single = connections.get("playwright");
            return single != null ? single : (playwrightPool.isEmpty() ? null : playwrightPool.get(0));
        }
        String sessionId = sessionIdOf(args);
        if (sessionId == null || sessionId.isBlank()) {
            // 无会话标识（理论上 playwright 工具均带 session_id）→ 兜底轮转到负载最小的槽
            int idx = Math.floorMod(playwrightSlotCounter.getAndIncrement(), playwrightPool.size());
            return playwrightPool.get(idx);
        }
        Integer slot = playwrightSlotOfSession.get(sessionId);
        if (slot == null) {
            int idx = Math.floorMod(playwrightSlotCounter.getAndIncrement(), playwrightPool.size());
            playwrightSlotOfSession.put(sessionId, idx);
            slot = idx;
            log.info("playwright 会话 {} 绑定槽位 playwright-{}（池大小 {}）", sessionId, idx, playwrightPool.size());
        }
        return playwrightPool.get(slot);
    }

    /** 从工具参数中提取 session_id（与 MCP Server 端 sessionIdOf 语义一致）。 */
    private String sessionIdOf(Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        Object sid = args.get("session_id");
        return sid == null ? null : String.valueOf(sid);
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
     * 检查指定 Server 是否可用。playwright 为连接池时返回任一子进程可用即可。
     */
    public boolean isAvailable(String serverName) {
        if ("playwright".equals(serverName) && playwrightPool.size() > 1) {
            return playwrightPool.stream().anyMatch(McpConnection::isAvailable);
        }
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
     * playwright 为连接池时广播到所有子进程（playwright 工具为非流式调用，此路径实际仅 llm 使用）。
     */
    public void cancelStreaming(String serverName) {
        if ("playwright".equals(serverName) && playwrightPool.size() > 1) {
            playwrightPool.forEach(McpConnection::cancelActiveStreaming);
            return;
        }
        McpConnection conn = connections.get(serverName);
        if (conn != null) {
            conn.cancelActiveStreaming();
        }
    }

    @PreDestroy
    public void stopAll() {
        connections.values().forEach(McpConnection::stop);
        connections.clear();
        playwrightPool.clear();
        playwrightSlotOfSession.clear();
        log.info("McpClientManager 已关闭所有 Server");
    }
}
