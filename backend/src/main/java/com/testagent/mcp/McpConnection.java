package com.testagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.testagent.common.GenerationCancelledException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * v2.6: 单个 MCP Server 连接封装。
 * v5.14: 升级为单 reader 线程 + request id 多路复用，支持并行调用与按请求取消。
 */
public class McpConnection {

    private static final Logger log = LoggerFactory.getLogger(McpConnection.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger requestId = new AtomicInteger(0);
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<Integer, Consumer<String>> chunkHandlers = new ConcurrentHashMap<>();

    private final String name;
    private final String nodePath;
    private final String scriptPath;
    private final List<String> extraArgs;
    private final Map<String, String> env;
    private final long requestTimeoutSeconds;

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private Thread readerThread;
    private volatile boolean initialized = false;
    private volatile int activeStreamingId = -1;
    private volatile int cancelledStreamingId = -1;

    public McpConnection(String name, String nodePath, String scriptPath,
                         List<String> extraArgs, Map<String, String> env) {
        this(name, nodePath, scriptPath, extraArgs, env, 60);
    }

    public McpConnection(String name, String nodePath, String scriptPath,
                         List<String> extraArgs, Map<String, String> env, long requestTimeoutSeconds) {
        this.name = name;
        this.nodePath = nodePath;
        this.scriptPath = scriptPath;
        this.extraArgs = extraArgs != null ? extraArgs : new ArrayList<>();
        this.env = env != null ? env : new HashMap<>();
        this.requestTimeoutSeconds = requestTimeoutSeconds > 0 ? requestTimeoutSeconds : 60;
    }

    public void start() {
        try {
            String projectRoot = System.getProperty("user.dir");
            Path candidate = Path.of(projectRoot, "..", scriptPath).normalize();
            if (!java.nio.file.Files.exists(candidate)) {
                candidate = Path.of(projectRoot, scriptPath).normalize();
            }
            String fullPath = candidate.toString();

            List<String> command = new ArrayList<>();
            command.add(nodePath);
            command.add(fullPath);
            command.addAll(extraArgs);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(projectRoot).getParentFile());
            pb.redirectErrorStream(false);
            pb.environment().putAll(env);

            process = pb.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            log.info("MCP Server [{}] 进程已启动: {} {}", name, nodePath, fullPath);
            startReader();
            initialize();
        } catch (Exception e) {
            log.warn("MCP Server [{}] 启动失败: {}", name, e.getMessage());
            initialized = false;
        }
    }

    private void startReader() {
        readerThread = new Thread(this::readLoop, "mcp-reader-" + name);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        try {
            String line;
            while ((line = stdout.readLine()) != null) {
                JsonNode msg = objectMapper.readTree(line);
                if (msg.has("method") && !msg.has("id")) {
                    handleNotification(msg);
                    continue;
                }
                if (msg.has("id")) {
                    int rid = msg.path("id").asInt();
                    CompletableFuture<JsonNode> future = pending.remove(rid);
                    if (future != null) {
                        future.complete(msg);
                    } else {
                        log.debug("MCP [{}] 忽略未知 response id={}", name, rid);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("MCP [{}] stdout 已关闭: {}", name, e.getMessage());
        } catch (Exception e) {
            log.warn("MCP [{}] reader 异常: {}", name, e.getMessage());
        } finally {
            log.warn("MCP [{}] reader 线程退出，失败所有 pending 请求", name);
            IOException closed = new IOException("MCP Server [" + name + "] stdout 已关闭");
            pending.forEach((id, f) -> f.completeExceptionally(closed));
            pending.clear();
        }
    }

    private void handleNotification(JsonNode msg) {
        String method = msg.path("method").asText();
        if (!"notifications/llm_chunk".equals(method)) {
            return;
        }
        int rid = msg.path("params").path("request_id").asInt(-1);
        Consumer<String> handler = rid <= 0 ? null : chunkHandlers.get(rid);
        if (handler == null) {
            // 兼容通知未携带/错配 request_id：回退到当前唯一流式请求
            handler = chunkHandlers.get(activeStreamingId);
        }
        if (handler == null) {
            return;
        }
        String chunkText = msg.path("params").path("text").asText("");
        if (!chunkText.isEmpty()) {
            handler.accept(chunkText);
        }
    }

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

    private void ensureAlive() throws Exception {
        if (!initialized || process == null || !process.isAlive()) {
            log.warn("MCP [{}] 未启动或已死, 尝试重启...", name);
            start();
            if (!initialized) {
                throw new IllegalStateException("MCP Server [" + name + "] 未启动");
            }
        }
    }

    public synchronized String callTool(String toolName, Map<String, Object> args) throws Exception {
        return callToolWithMeta(toolName, args).getText();
    }

    public McpToolResult callToolWithMeta(String toolName, Map<String, Object> args) throws Exception {
        ensureAlive();
        log.info("MCP [{}] callTool: {}, args keys={}", name, toolName, args.keySet());
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", objectMapper.valueToTree(args));

        JsonNode response = sendRequest("tools/call", params);
        if (response.has("error")) {
            throw new RuntimeException("MCP [" + name + "] 错误: " + response.get("error"));
        }
        JsonNode result = response.path("result");
        if (result.path("isError").asBoolean(false)) {
            String errText = result.path("content").path(0).path("text").asText("MCP 执行失败");
            throw new RuntimeException("MCP [" + name + "] 执行失败: " + errText);
        }
        JsonNode content = result.path("content");
        if (content.isArray() && !content.isEmpty()) {
            String text = content.get(0).path("text").asText("");
            JsonNode metadata = readMetadata(content);
            log.info("MCP [{}] callTool 返回, 长度={}", name, text.length());
            return new McpToolResult(text, metadata);
        }
        throw new RuntimeException("MCP Server [" + name + "] 返回内容为空");
    }

    public synchronized String callToolStreaming(
            String toolName, Map<String, Object> args,
            Consumer<String> chunkConsumer) throws Exception {
        return callToolStreamingWithMeta(toolName, args, chunkConsumer).getText();
    }

    public McpToolResult callToolStreamingWithMeta(
            String toolName, Map<String, Object> args,
            Consumer<String> chunkConsumer) throws Exception {
        ensureAlive();

        int id = requestId.incrementAndGet();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", "tools/call");
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", objectMapper.valueToTree(args));
        request.set("params", params);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        if (chunkConsumer != null) {
            chunkHandlers.put(id, chunkConsumer);
        }
        activeStreamingId = id;

        try {
            writeLine(objectMapper.writeValueAsString(request));
            log.info("MCP [{}] → callToolStreaming: {}, id={}, stream=true", name, toolName, id);
            JsonNode response;
            try {
                response = future.get(requestTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("MCP [{}] 流式请求超时: tool={}, id={}, timeout={}s",
                        name, toolName, id, requestTimeoutSeconds);
                if (activeStreamingId == id && process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
                throw new IOException("MCP [" + name + "] 请求超时: " + toolName);
            } catch (ExecutionException e) {
                if (cancelledStreamingId == id) {
                    throw new GenerationCancelledException("用户取消生成");
                }
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                throw new IOException(cause);
            }

            if (cancelledStreamingId == id) {
                throw new GenerationCancelledException("用户取消生成");
            }
            if (response.has("error")) {
                throw new RuntimeException("MCP [" + name + "] 错误: " + response.get("error"));
            }
            JsonNode result = response.path("result");
            if (result.path("isError").asBoolean(false)) {
                String errText = result.path("content").path(0).path("text").asText("MCP 执行失败");
                throw new RuntimeException("MCP [" + name + "] 执行失败: " + errText);
            }
            JsonNode content = result.path("content");
            if (content.isArray() && !content.isEmpty()) {
                String text = content.get(0).path("text").asText("");
                JsonNode metadata = readMetadata(content);
                log.info("MCP [{}] callToolStreaming 完成, id={}, 长度={}", name, id, text.length());
                return new McpToolResult(text, metadata);
            }
            throw new RuntimeException("MCP Server [" + name + "] 返回内容为空");
        } finally {
            chunkHandlers.remove(id);
            pending.remove(id);
            if (activeStreamingId == id) {
                activeStreamingId = -1;
            }
        }
    }

    /**
     * 取消当前流式请求：通过 llm_cancel 通知子进程中断，并让等待方抛出取消异常。
     */
    public void cancelActiveStreaming() {
        int id = activeStreamingId;
        if (id <= 0) {
            return;
        }
        cancelledStreamingId = id;
        try {
            callTool("llm_cancel", Map.of("request_id", id));
        } catch (Exception e) {
            log.debug("MCP [{}] llm_cancel 调用异常: {}", name, e.getMessage());
        }
        // 兜底：llm_cancel 不可达时直接终止流式连接（已独立连接，不影响 chat/embedding）
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            log.info("MCP [{}] 取消时强制终止流式进程", name);
        }
    }

    private JsonNode readMetadata(JsonNode content) {
        if (content.size() > 1 && content.get(1).has("text")) {
            try {
                return objectMapper.readTree(content.get(1).path("text").asText("{}"));
            } catch (Exception e) {
                log.debug("MCP [{}] 元数据解析失败: {}", name, e.getMessage());
            }
        }
        return null;
    }

    public boolean isAvailable() {
        return initialized && process != null && process.isAlive();
    }

    public String getName() {
        return name;
    }

    private JsonNode sendRequest(String method, JsonNode params) throws Exception {
        int id = requestId.incrementAndGet();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        String json = objectMapper.writeValueAsString(request);
        log.info("MCP [{}] → sendRequest method={}, id={}, 等待响应...", name, method, id);
        try {
            writeLine(json);
            return future.get(requestTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            log.warn("MCP [{}] 请求超时: method={}, id={}, timeout={}s",
                    name, method, id, requestTimeoutSeconds);
            throw new IOException("MCP [" + name + "] 请求超时: " + method);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new IOException(cause);
        } finally {
            pending.remove(id);
        }
    }

    private synchronized void writeLine(String json) throws IOException {
        stdin.write(json);
        stdin.newLine();
        stdin.flush();
    }

    private void sendNotification(String method) throws Exception {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        writeLine(objectMapper.writeValueAsString(notification));
    }

    public void stop() {
        initialized = false;
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            log.info("MCP Server [{}] 进程已关闭", name);
        }
        IOException closed = new IOException("MCP Server [" + name + "] 已停止");
        pending.forEach((id, f) -> f.completeExceptionally(closed));
        pending.clear();
    }
}
