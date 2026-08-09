package com.testagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v2.2: Java MCP 客户端。
 * 通过 ProcessBuilder 启动 Node.js MCP Server 子进程，
 * 使用 JSON-RPC 2.0 over stdio 进行通信。
 */
@Component
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger requestId = new AtomicInteger(0);

    @Value("${llm.api-key:}")
    private String llmApiKey;

    @Value("${llm.base-url:https://api.xiaomimimo.com/v1}")
    private String llmBaseUrl;

    @Value("${llm.model:gpt-4o}")
    private String llmModel;

    @Value("${mcp.server.node-path:node}")
    private String nodePath;

    @Value("${mcp.server.script-path:mcp-server/index.js}")
    private String scriptPath;

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private volatile boolean initialized = false;

    @PostConstruct
    public void start() {
        try {
            String projectRoot = System.getProperty("user.dir");
            // user.dir 在 backend 目录下运行时是 backend/，需要回退一级
            String fullPath = Path.of(projectRoot, "..", scriptPath).normalize().toString();

            ProcessBuilder pb = new ProcessBuilder(nodePath, fullPath);
            pb.directory(new File(projectRoot).getParentFile());
            pb.redirectErrorStream(false);

            // 传递 LLM 配置作为环境变量
            Map<String, String> env = pb.environment();
            env.put("OPENAI_API_KEY", llmApiKey);
            env.put("OPENAI_BASE_URL", llmBaseUrl);
            env.put("OPENAI_MODEL", llmModel);

            process = pb.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));

            log.info("MCP Server 进程已启动: {} {}", nodePath, fullPath);

            // MCP initialize 握手
            initialize();

        } catch (Exception e) {
            log.warn("MCP Server 启动失败，将降级为直调 LLM: {}", e.getMessage());
            initialized = false;
        }
    }

    /**
     * MCP initialize 握手。
     */
    private void initialize() throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.putObject("capabilities");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "aicasetest-backend");
        clientInfo.put("version", "2.2");

        JsonNode response = sendRequest("initialize", params);
        if (response != null && response.has("result")) {
            // 发送 initialized 通知
            sendNotification("notifications/initialized");
            initialized = true;
            log.info("MCP 握手成功: {}", response.path("result").path("serverInfo"));
        } else {
            log.warn("MCP 握手失败: {}", response);
            initialized = false;
        }
    }

    /**
     * 调用 MCP 工具。
     * @param toolName 工具名称
     * @param args 工具参数
     * @return 工具返回的文本内容（content[0].text）
     */
    public String callTool(String toolName, Map<String, Object> args) throws Exception {
        if (!initialized || process == null || !process.isAlive()) {
            // 尝试重启
            start();
            if (!initialized) {
                throw new IllegalStateException("MCP Server 未启动");
            }
        }

        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", objectMapper.valueToTree(args));

        JsonNode response = sendRequest("tools/call", params);
        if (response == null) {
            throw new IllegalStateException("MCP 返回 null");
        }

        if (response.has("error")) {
            throw new RuntimeException("MCP 错误: " + response.get("error"));
        }

        JsonNode content = response.path("result").path("content");
        if (content.isArray() && !content.isEmpty()) {
            return content.get(0).path("text").asText("");
        }

        throw new RuntimeException("MCP 返回内容为空");
    }

    public boolean isAvailable() {
        return initialized && process != null && process.isAlive();
    }

    /**
     * 发送 JSON-RPC 2.0 请求并等待响应。
     */
    private JsonNode sendRequest(String method, JsonNode params) throws Exception {
        int id = requestId.incrementAndGet();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        String json = objectMapper.writeValueAsString(request);
        log.debug("MCP →: {}", json);
        stdin.write(json);
        stdin.newLine();
        stdin.flush();

        // 读取响应（单行 JSON）
        String line = stdout.readLine();
        if (line == null) {
            throw new IOException("MCP Server stdout 已关闭");
        }
        log.debug("MCP ←: {}", line);
        return objectMapper.readTree(line);
    }

    /**
     * 发送 JSON-RPC 2.0 通知（不需要响应）。
     */
    private void sendNotification(String method) throws Exception {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);

        String json = objectMapper.writeValueAsString(notification);
        stdin.write(json);
        stdin.newLine();
        stdin.flush();
    }

    @PreDestroy
    public void stop() {
        initialized = false;
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            log.info("MCP Server 进程已关闭");
        }
    }
}
