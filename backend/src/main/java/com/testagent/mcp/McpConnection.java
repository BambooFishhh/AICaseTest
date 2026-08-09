package com.testagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v2.6: 单个 MCP Server 连接封装。
 * 管理 Process + stdin/stdout + JSON-RPC 2.0 通信。
 * 不是 Spring Bean，由 McpClientManager 创建和管理。
 */
public class McpConnection {

    private static final Logger log = LoggerFactory.getLogger(McpConnection.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger requestId = new AtomicInteger(0);

    private final String name;
    private final String nodePath;
    private final String scriptPath;
    private final List<String> extraArgs;
    private final Map<String, String> env;

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private volatile boolean initialized = false;

    public McpConnection(String name, String nodePath, String scriptPath,
                         List<String> extraArgs, Map<String, String> env) {
        this.name = name;
        this.nodePath = nodePath;
        this.scriptPath = scriptPath;
        this.extraArgs = extraArgs != null ? extraArgs : new ArrayList<>();
        this.env = env != null ? env : new HashMap<>();
    }

    /**
     * 启动子进程 + MCP 握手。
     */
    public void start() {
        try {
            String projectRoot = System.getProperty("user.dir");
            // user.dir 在 backend 目录下运行时是 backend/，需要回退一级到项目根目录
            String fullPath = Path.of(projectRoot, "..", scriptPath).normalize().toString();

            List<String> command = new ArrayList<>();
            command.add(nodePath);
            command.add(fullPath);
            command.addAll(extraArgs);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(projectRoot).getParentFile());
            pb.redirectErrorStream(false);

            pb.environment().putAll(env);

            process = pb.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));

            log.info("MCP Server [{}] 进程已启动: {} {}", name, nodePath, fullPath);

            initialize();

        } catch (Exception e) {
            log.warn("MCP Server [{}] 启动失败: {}", name, e.getMessage());
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
        clientInfo.put("version", "2.6");

        JsonNode response = sendRequest("initialize", params);
        if (response != null && response.has("result")) {
            sendNotification("notifications/initialized");
            initialized = true;
            log.info("MCP Server [{}] 握手成功: {}", name, response.path("result").path("serverInfo"));
        } else {
            log.warn("MCP Server [{}] 握手失败: {}", name, response);
            initialized = false;
        }
    }

    /**
     * 调用工具（synchronized 防止 stdio 并发乱序）。
     *
     * @param toolName 工具名称
     * @param args     工具参数
     * @return 工具返回的文本内容（content[0].text）
     */
    public synchronized String callTool(String toolName, Map<String, Object> args) throws Exception {
        if (!initialized || process == null || !process.isAlive()) {
            // 尝试重启
            start();
            if (!initialized) {
                throw new IllegalStateException("MCP Server [" + name + "] 未启动");
            }
        }

        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", objectMapper.valueToTree(args));

        JsonNode response = sendRequest("tools/call", params);
        if (response == null) {
            throw new IllegalStateException("MCP Server [" + name + "] 返回 null");
        }

        if (response.has("error")) {
            throw new RuntimeException("MCP [" + name + "] 错误: " + response.get("error"));
        }

        JsonNode content = response.path("result").path("content");
        if (content.isArray() && !content.isEmpty()) {
            return content.get(0).path("text").asText("");
        }

        throw new RuntimeException("MCP Server [" + name + "] 返回内容为空");
    }

    public boolean isAvailable() {
        return initialized && process != null && process.isAlive();
    }

    public String getName() {
        return name;
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
        log.debug("MCP [{}] →: {}", name, json);
        stdin.write(json);
        stdin.newLine();
        stdin.flush();

        // 读取响应（单行 JSON）
        String line = stdout.readLine();
        if (line == null) {
            throw new IOException("MCP Server [" + name + "] stdout 已关闭");
        }
        log.debug("MCP [{}] ←: {}", name, line);
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

    /**
     * 关闭子进程。
     */
    public void stop() {
        initialized = false;
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            log.info("MCP Server [{}] 进程已关闭", name);
        }
    }
}
