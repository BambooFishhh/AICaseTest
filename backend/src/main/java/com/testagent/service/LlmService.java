package com.testagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.common.BusinessException;
import com.testagent.common.GenerationCancelledException;
import com.testagent.common.LlmCircuitBreaker;
import com.testagent.common.LlmRetryPolicy;
import com.testagent.dto.LlmCallResult;
import com.testagent.mcp.McpClientManager;
import com.testagent.mcp.McpToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * LLM 调用服务。
 * v1.0: OkHttp 直调 OpenAI API
 * v2.3: 重构为通过 MCP 协议调用独立 MCP Server
 * v2.6: 适配 McpClientManager 多 Server 架构
 * v6.0: 文本/流式/JSON/Embedding 层改用 Spring AI OpenAI starter，
 *       仅多模态(chatWithImage / multimodal_element_locate)与浏览器/工具仍走 MCP。
 * 注意：Spring AI 1.0.0 OpenAI starter 无法透传 DashScope 的 enable_thinking，
 *       因此 analysis/generation 思考开关在该链路为咨询性配置（详见迁移文档 PoC 对比）。
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${llm.provider:openai}")
    private String provider;

    @Value("${llm.model:gpt-4o}")
    private String model;

    // v5.14: 按任务粒度控制思考模式——分析类任务保留，生成/评审默认关闭
    // v6.0: 咨询性配置（OpenAI starter 不透传 enable_thinking，见类注释）
    @Value("${llm.thinking.analysis:true}")
    private boolean analysisThinking;

    @Value("${llm.thinking.generation:false}")
    private boolean generationThinking;

    // v6.1 (B 方案): 统一 prompt 上限，防止超大上下文触发 Idle timeout
    // v7.13: 默认 60000 → 300000；v8.4: → 500000 适配 256k context 模型（此处为保险丝，正常不触发）
    @Value("${llm.max-prompt-chars:500000}")
    private int maxPromptChars = 500000;

    // v6.5: LLM 重试次数（分类 + 抖动，4xx 不重试）
    @Value("${llm.retry.max-attempts:3}")
    private int maxRetries = 3;

    // v7.3(L8): 输出上限配置化（原硬编码 16384）；v8.4: 默认 16384 → 32768，
    // 减少单轮高密度用例顶满输出上限导致的流式 JSON 截断（适配 256k context 模型）
    @Value("${llm.max-tokens:32768}")
    private int maxTokens = 32768;

    // v8.4fix: 流级总超时看门狗——底层 read-timeout 在网关持续下发心跳时不会触发，
    // 无看门狗时挂死的流会永久占用 generation 线程与 SSE 连接
    @Value("${llm.stream-total-timeout-ms:900000}")
    private long streamTotalTimeoutMs = 900000;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private McpClientManager mcpClientManager;

    @Autowired
    private TelemetryService telemetryService;

    @Autowired
    private LlmCircuitBreaker llmCircuitBreaker;

    // v8.6.2(9.7): 出参契约校验器——字段默认 null（直 new 单测不受影响），null 时跳过校验
    private LlmSchemaValidator schemaValidator;

    @Autowired(required = false)
    void setSchemaValidator(LlmSchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
    }

    // v8.7.1(9.5.2): 指标门面——no-op 兜底
    private com.testagent.observability.MetricsFacade metrics = new com.testagent.observability.MetricsFacade();

    @Autowired(required = false)
    void setMetricsFacade(com.testagent.observability.MetricsFacade metrics) {
        this.metrics = metrics;
    }

    // v8.8.1(10.1/10.2): 多供应商注册与降级路由——providers 未注入（直 new 单测）时单通道行为
    private com.testagent.config.LlmProviders providers;

    @Autowired(required = false)
    void setProviders(com.testagent.config.LlmProviders providers) {
        this.providers = providers;
    }

    // v8.8.1(10.2): 本次线程最近一次生成是否走了降级通道——由 TestGeneratorAgent 写入报告、
    // TestCaseService 写入 complete 事件；同线程消费后即清除
    private final ThreadLocal<String> degradedProvider = new ThreadLocal<>();

    public String consumeDegradedProvider() {
        String value = degradedProvider.get();
        degradedProvider.remove();
        return value;
    }

    // seam：按通道名取 ChatClient（primary 复用自动装配 builder；fallback 由 LlmProviders 构建）
    protected ChatClient chatClientFor(String providerName) {
        if ("fallback".equals(providerName)) {
            ChatClient client = providers == null ? null : providers.fallbackChatClient();
            if (client == null) {
                throw new BusinessException(50002, "降级通道未配置", HttpStatus.SERVICE_UNAVAILABLE);
            }
            return client;
        }
        return chatClientBuilder.build();
    }

    private String breakerChannelOf(String providerName) {
        return "fallback".equals(providerName)
                ? LlmCircuitBreaker.CHANNEL_TEXT + ":fallback"
                : LlmCircuitBreaker.CHANNEL_TEXT;
    }

    // v8.9.2(12.2): 限流通道映射——主/降级独立配额（fallback 文本与流式共用 fallback-text 配额）
    private LlmRateLimiter rateLimiter = new LlmRateLimiter();

    @Autowired(required = false)
    void setRateLimiter(LlmRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    private String rateChannelOf(String providerName, boolean streaming) {
        if ("fallback".equals(providerName)) {
            return LlmRateLimiter.CHANNEL_FALLBACK_TEXT;
        }
        return streaming ? LlmRateLimiter.CHANNEL_STREAM : LlmRateLimiter.CHANNEL_TEXT;
    }

    @jakarta.annotation.PostConstruct
    void registerMetrics() {
        // v8.7.1: 启动零值预注册
        metrics.registerCounter("gen_retry_reset_total");
        metrics.registerCounter("gen_stream_truncated_total");
        // v8.8.1(10.2): 降级路由使用计数
        metrics.registerCounter("llm_fallback_used_total", "channel", "text");
        metrics.registerCounter("llm_fallback_used_total", "channel", "text-stream");
        metrics.registerCounter("llm_fallback_used_total", "channel", "embedding");
    }

    // v1.4: 重试延迟基数（v6.5 起叠加随机抖动）
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 4000};

    // v6.0: 全局单流取消字段已于 v7.3(L1) 删除——并发生成时全局取消会误杀其他请求的流。
    // 取消信号改为 per-request 由调用方传入（BooleanSupplier），见 chatStreamingWithUsage。

    /**
     * v7.10(L3): thinking 配置诚实化——启动时告知该配置不生效（未标记的降级 = 幻觉配置）。
     * Spring AI 1.0.0 OpenAI starter 无法透传 DashScope 的 enable_thinking，
     * 用户开启 llm.thinking.* 后以为已生效，实际行为与关闭完全相同。
     */
    @jakarta.annotation.PostConstruct
    void warnConsultativeThinking() {
        if (analysisThinking || generationThinking) {
            log.warn("[LLM] llm.thinking.* 已开启（analysis={}, generation={}），但当前 Spring AI OpenAI starter "
                            + "无法透传 enable_thinking，该配置不生效（咨询性配置，仅记录意图）",
                    analysisThinking, generationThinking);
        }
    }

    /**
     * v6.0: 检查 Spring AI ChatModel/EmbeddingModel 是否可用（替代旧版 MCP llm 可用性检查）。
     */
    public boolean isConfigured() {
        return chatModel != null;
    }

    /**
     * v6.0: 通过 Spring AI ChatClient 调用 LLM 文本对话。
     */
    public String chat(String systemPrompt, String userPrompt, double temperature) {
        return chatWithUsage(systemPrompt, userPrompt, temperature, generationThinking).getText();
    }

    /**
     * v5.14: 分析类调用（PRD 解析/状态机提取）保留思考模式。
     */
    public String chatWithAnalysis(String systemPrompt, String userPrompt, double temperature) {
        return chatWithUsage(systemPrompt, userPrompt, temperature, analysisThinking).getText();
    }

    /**
     * v5.14: chat() 的埋点版本，返回耗时与 token usage。
     */
    public LlmCallResult chatWithUsage(String systemPrompt, String userPrompt, double temperature) {
        return chatWithUsage(systemPrompt, userPrompt, temperature, generationThinking);
    }

    private LlmCallResult chatWithUsage(String systemPrompt, String userPrompt, double temperature,
                                        boolean enableThinking) {
        // v8.9.2(12.6/C6): 入口先清残留——池化线程上一次异常路径未消费的旧值不再串台
        degradedProvider.remove();
        // v8.8.1(10.2): 降级路由——主通道重试耗尽/熔断打开时切换 fallback（未配置则原样抛出）
        try {
            return chatWithUsageOn("primary", systemPrompt, userPrompt, temperature, enableThinking);
        } catch (BusinessException primaryEx) {
            if (providers == null || !providers.fallbackEnabled()) {
                throw primaryEx;
            }
            metrics.increment("llm_fallback_used_total", "channel", "text");
            degradedProvider.set("fallback");
            log.warn("[LLM] 主通道不可用，切换降级通道: {}", primaryEx.getMessage());
            try {
                return chatWithUsageOn("fallback", systemPrompt, userPrompt, temperature, enableThinking);
            } catch (Exception fallbackEx) {
                throw new BusinessException(50300,
                        "主/降级 LLM 通道均不可用: " + fallbackEx.getMessage(),
                        HttpStatus.SERVICE_UNAVAILABLE);
            }
        }
    }

    private LlmCallResult chatWithUsageOn(String providerName, String systemPrompt, String userPrompt,
                                          double temperature, boolean enableThinking) {
        String channel = breakerChannelOf(providerName);
        // v8.9.2(12.2): 入口限流——通道配额内执行（release 于 finally），50300 可重试语义供降级路由
        return rateLimiter.execute(rateChannelOf(providerName, false), () ->
                chatWithUsageBody(providerName, channel, systemPrompt, userPrompt, temperature, enableThinking));
    }

    private LlmCallResult chatWithUsageBody(String providerName, String channel, String systemPrompt,
                                            String userPrompt, double temperature, boolean enableThinking) {
        userPrompt = boundPrompt(userPrompt);
        log.info("[LLM] chat() 开始, provider={}, model={}, prompt长度={}, thinking={}",
                providerName, model, userPrompt == null ? 0 : userPrompt.length(), enableThinking);
        if (llmCircuitBreaker != null && !llmCircuitBreaker.allowRequest(channel)) {
            throw new BusinessException(50002, "LLM 熔断打开，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
        Exception lastException = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                long start = System.currentTimeMillis();
                OpenAiChatOptions options = buildOptions(temperature, enableThinking);
                ChatResponse response = chatClientFor(providerName).prompt()
                        .system(systemPrompt)
                        .user(userPrompt == null ? "" : userPrompt)
                        .options(options)
                        .call()
                        .chatResponse();
                String text = extractText(response);
                LlmCallResult call = toLlmCallResult(text, response == null ? null : response.getMetadata().getUsage(),
                        start, null);
                telemetryService.recordLlmCall(call);
                log.info("[LLM] Spring AI 返回, 耗时={}ms, 响应长度={}, usage={}",
                        System.currentTimeMillis() - start, text == null ? 0 : text.length(),
                        Map.of("prompt", call.getPromptTokens(), "completion", call.getCompletionTokens(),
                                "total", call.getTotalTokens()));
                if (llmCircuitBreaker != null) {
                    llmCircuitBreaker.onSuccess(channel);
                }
                return call;
            } catch (Exception e) {
                lastException = e;
                log.warn("[LLM] attempt {} 失败: {}", attempt + 1, e.getMessage());
                if (e instanceof GenerationCancelledException) {
                    throw (GenerationCancelledException) e;
                }
                if (!LlmRetryPolicy.isRetryable(e)) {
                    log.warn("[LLM] 非可重试错误，停止重试: {}", e.getMessage());
                    break;
                }
            }
            if (attempt < maxRetries - 1) {
                try {
                    long delay = retryDelayMs(attempt);
                    log.info("[LLM] 等待 {}ms 后重试...", delay);
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // v7.3(L2): 不可重试错误（4xx 配置类）不计入熔断——API Key 填错不应打满熔断拖垮全系统
        if (llmCircuitBreaker != null && LlmRetryPolicy.isRetryable(lastException)) {
            llmCircuitBreaker.onFailure(channel);
        }
        throw new BusinessException(50002,
                "LLM调用失败（已尝试" + maxRetries + "次）: " + (lastException != null ? lastException.getMessage() : "unknown"),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * v3.7: 流式调用 LLM。chunkConsumer 接收逐块文本，方法返回完整响应。
     * v6.0: 改为 Spring AI ChatClient.stream()，Flux<ChatResponse> 仅在本类内部适配。
     * v7.3(L1): 该重载不支持中途取消（cancelSignal=null），仅供测试/内部使用；
     *           生产链路请用带 cancelSignal 的重载，取消只作用于本次请求。
     */
    public String chatStreaming(String systemPrompt, String userPrompt,
                                double temperature,
                                Consumer<String> chunkConsumer) {
        return chatStreamingWithUsage(systemPrompt, userPrompt, temperature, chunkConsumer,
                null, generationThinking).getText();
    }

    /**
     * v7.3(L1): 流式调用（支持 per-request 取消）。cancelSignal 返回 true 时中断本次流。
     */
    public String chatStreaming(String systemPrompt, String userPrompt,
                                double temperature,
                                Consumer<String> chunkConsumer,
                                java.util.function.BooleanSupplier cancelSignal) {
        return chatStreamingWithUsage(systemPrompt, userPrompt, temperature, chunkConsumer,
                cancelSignal, generationThinking, null).getText();
    }

    /**
     * v8.4fix: 带重试重置钩子的流式调用。流中途失败重试前，若已向消费者推送过内容，
     * 先回调 retryResetHook（调用方清空已累积的解析缓冲/通知前端），避免半截+全量重复推送。
     */
    public String chatStreaming(String systemPrompt, String userPrompt,
                                double temperature,
                                Consumer<String> chunkConsumer,
                                java.util.function.BooleanSupplier cancelSignal,
                                Runnable retryResetHook) {
        return chatStreamingWithUsage(systemPrompt, userPrompt, temperature, chunkConsumer,
                cancelSignal, generationThinking, retryResetHook).getText();
    }

    /**
     * v5.14: chatStreaming() 的埋点版本，返回耗时、首 token 耗时与 token usage。
     */
    public LlmCallResult chatStreamingWithUsage(String systemPrompt, String userPrompt,
                                                double temperature,
                                                Consumer<String> chunkConsumer) {
        return chatStreamingWithUsage(systemPrompt, userPrompt, temperature, chunkConsumer,
                null, generationThinking, null);
    }

    private LlmCallResult chatStreamingWithUsage(String systemPrompt, String userPrompt,
                                                 double temperature,
                                                 Consumer<String> chunkConsumer,
                                                 java.util.function.BooleanSupplier cancelSignal,
                                                 boolean enableThinking) {
        return chatStreamingWithUsage(systemPrompt, userPrompt, temperature, chunkConsumer,
                cancelSignal, enableThinking, null);
    }

    private LlmCallResult chatStreamingWithUsage(String systemPrompt, String userPrompt,
                                                 double temperature,
                                                 Consumer<String> chunkConsumer,
                                                 java.util.function.BooleanSupplier cancelSignal,
                                                 boolean enableThinking,
                                                 Runnable retryResetHook) {
        // v8.9.2(12.6/C6): 同 chat 入口——先清残留降级标注
        degradedProvider.remove();
        // v8.8.1(10.2): 降级路由（流式）——主通道整体失败后切 fallback；切换前先清已推送草稿
        try {
            return chatStreamingOn("primary", systemPrompt, userPrompt, temperature, chunkConsumer,
                    cancelSignal, enableThinking, retryResetHook);
        } catch (BusinessException primaryEx) {
            if (providers == null || !providers.fallbackEnabled()) {
                throw primaryEx;
            }
            metrics.increment("llm_fallback_used_total", "channel", "text-stream");
            degradedProvider.set("fallback");
            log.warn("[LLM] 流式主通道不可用，切换降级通道: {}", primaryEx.getMessage());
            if (retryResetHook != null) {
                try {
                    retryResetHook.run();
                } catch (Exception ignored) {
                    // 清态钩子失败不阻断降级
                }
            }
            try {
                return chatStreamingOn("fallback", systemPrompt, userPrompt, temperature, chunkConsumer,
                        cancelSignal, enableThinking, retryResetHook);
            } catch (Exception fallbackEx) {
                throw new BusinessException(50300,
                        "主/降级 LLM 通道均不可用: " + fallbackEx.getMessage(),
                        HttpStatus.SERVICE_UNAVAILABLE);
            }
        }
    }

    private LlmCallResult chatStreamingOn(String providerName, String systemPrompt, String userPrompt,
                                          double temperature,
                                          Consumer<String> chunkConsumer,
                                          java.util.function.BooleanSupplier cancelSignal,
                                          boolean enableThinking,
                                          Runnable retryResetHook) {
        String channel = breakerChannelOf(providerName);
        // v8.9.2(12.2): 流式入口同受通道配额约束
        return rateLimiter.execute(rateChannelOf(providerName, true), () ->
                chatStreamingBody(providerName, channel, systemPrompt, userPrompt, temperature,
                        chunkConsumer, cancelSignal, enableThinking, retryResetHook));
    }

    private LlmCallResult chatStreamingBody(String providerName, String channel, String systemPrompt,
                                            String userPrompt, double temperature,
                                            Consumer<String> chunkConsumer,
                                            java.util.function.BooleanSupplier cancelSignal,
                                            boolean enableThinking,
                                            Runnable retryResetHook) {
        userPrompt = boundPrompt(userPrompt);
        log.info("[LLM] chatStreaming() 开始, provider={}, prompt长度={}, thinking={}",
                providerName, userPrompt == null ? 0 : userPrompt.length(), enableThinking);
        if (llmCircuitBreaker != null && !llmCircuitBreaker.allowRequest(channel)) {
            throw new BusinessException(50002, "LLM 熔断打开，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
        Exception lastException = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            // v8.4fix: 标记本次尝试是否已向消费者推送过内容（重试前需重置）
            java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean(false);
            try {
                long start = System.currentTimeMillis();
                // v8.4fix: 流级总超时看门狗（底层心跳保活时 read-timeout 不触发）
                long deadline = start + streamTotalTimeoutMs;
                AtomicLong firstTokenAt = new AtomicLong(0);
                StringBuilder full = new StringBuilder();
                AtomicReference<Usage> usageRef = new AtomicReference<>();
                AtomicReference<Throwable> errorRef = new AtomicReference<>();
                CountDownLatch done = new CountDownLatch(1);

                OpenAiChatOptions options = buildOptions(temperature, enableThinking);
                var flux = chatClientFor(providerName).prompt()
                        .system(systemPrompt)
                        .user(userPrompt == null ? "" : userPrompt)
                        .options(options)
                        .stream()
                        .chatResponse();

                // v7.3(L1): 取消只检查调用方传入的 per-request 信号，不再有全局标志
                Disposable disposable = flux.doOnNext(response -> {
                    if (cancelSignal != null && cancelSignal.getAsBoolean()) {
                        throw new GenerationCancelledException("用户取消生成");
                    }
                    String delta = extractText(response);
                    if (delta != null && !delta.isEmpty()) {
                        firstTokenAt.compareAndSet(0, System.currentTimeMillis());
                        full.append(delta);
                        delivered.set(true);
                        if (chunkConsumer != null) {
                            chunkConsumer.accept(delta);
                        }
                    }
                    Usage usage = response.getMetadata().getUsage();
                    if (usage != null) {
                        usageRef.set(usage);
                    }
                // v7.11(L14): error 信号必须同时释放 latch——Reactor 的 error 不触发
                // doOnComplete/doOnCancel，否则流中断时外层 await 轮询永不退出（线程死循环）
                }).doOnError(error -> {
                    errorRef.set(error);
                    done.countDown();
                })
                  .doOnComplete(done::countDown)
                  .doOnCancel(done::countDown)
                  .subscribe();

                try {
                    while (!done.await(200, TimeUnit.MILLISECONDS)) {
                        if (cancelSignal != null && cancelSignal.getAsBoolean()) {
                            disposable.dispose();
                            throw new GenerationCancelledException("用户取消生成");
                        }
                        // v8.4fix: 超过流级总时长主动中断，异常消息含 timeout 字样会被重试策略识别为可重试
                        if (System.currentTimeMillis() > deadline) {
                            disposable.dispose();
                            // v8.7.1(9.5.2): 流级看门狗超时进指标——网关保活掩盖的挂死流劣化信号
                            metrics.increment("gen_stream_truncated_total");
                            throw new RuntimeException("LLM stream total timeout after " + streamTotalTimeoutMs + "ms");
                        }
                    }
                } finally {
                    if (!disposable.isDisposed()) {
                        disposable.dispose();
                    }
                }

                // v7.11(L14): errorRef 检查提前——真实错误优先于取消判定，
                // 防止网络错误被谎报为"用户取消"；doOnNext 内取消异常经 error 信号回流时
                // 保持 GenerationCancelledException 语义原样透传
                Throwable error = errorRef.get();
                if (error instanceof GenerationCancelledException gce) {
                    throw gce;
                }
                if (error != null) {
                    throw new RuntimeException(error);
                }
                if (cancelSignal != null && cancelSignal.getAsBoolean()) {
                    throw new GenerationCancelledException("用户取消生成");
                }

                long elapsed = System.currentTimeMillis() - start;
                Long firstTokenMs = firstTokenAt.get() == 0 ? null : firstTokenAt.get() - start;
                LlmCallResult call = toLlmCallResult(full.toString(), usageRef.get(), start, firstTokenMs);
                telemetryService.recordLlmCall(call);
                log.info("[LLM] chatStreaming 完成, 耗时={}ms, ttft={}ms, 响应长度={}, usage={}",
                        elapsed, firstTokenMs, full.length(),
                        Map.of("prompt", call.getPromptTokens(), "completion", call.getCompletionTokens(),
                                "total", call.getTotalTokens()));
                if (llmCircuitBreaker != null) {
                    llmCircuitBreaker.onSuccess(channel);
                }
                return call;
            } catch (Exception e) {
                lastException = e;
                log.warn("[LLM] chatStreaming attempt {} 失败: {}", attempt + 1, e.getMessage());
                if (e instanceof GenerationCancelledException) {
                    throw (GenerationCancelledException) e;
                }
                if (!LlmRetryPolicy.isRetryable(e)) {
                    log.warn("[LLM] 流式调用出现非可重试错误，停止重试: {}", e.getMessage());
                    break;
                }
            }
            if (attempt < maxRetries - 1) {
                // v8.4fix: 重试前若已推送过部分内容，通知调用方重置（清空解析缓冲/前端草稿），
                // 避免重试后全量重推造成重复用例/重复 SSE 输出
                if (delivered.get() && retryResetHook != null) {
                    try {
                        retryResetHook.run();
                        // v8.7.1(9.5.2): 重试重置次数进指标
                        metrics.increment("gen_retry_reset_total");
                        log.warn("[LLM] 流式重试前已通知调用方重置已推送内容 (attempt {})", attempt + 1);
                    } catch (Exception hookEx) {
                        log.warn("[LLM] retryResetHook 执行失败: {}", hookEx.getMessage());
                    }
                }
                try {
                    long delay = retryDelayMs(attempt);
                    log.info("[LLM] 等待 {}ms 后重试...", delay);
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // v7.3(L2): 不可重试错误不计入熔断（同 chat 链路）
        if (llmCircuitBreaker != null && LlmRetryPolicy.isRetryable(lastException)) {
            llmCircuitBreaker.onFailure(channel);
        }
        throw new BusinessException(50002,
                "LLM流式调用失败（已尝试" + maxRetries + "次）: " + (lastException != null ? lastException.getMessage() : "unknown"),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * v2.6: 通过 MCP 协议调用 llm_chat_with_image 工具（保留 MCP 多模态）。
     */
    public String chatWithImage(String systemPrompt, String userText, String imageBase64) {
        // v7.3(L2): 多模态通道独立熔断，与文本通道互不连坐
        if (llmCircuitBreaker != null && !llmCircuitBreaker.allowRequest(LlmCircuitBreaker.CHANNEL_MULTIMODAL)) {
            throw new BusinessException(50002, "LLM 熔断打开，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
        Exception lastException = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                long start = System.currentTimeMillis();
                McpToolResult result = mcpClientManager.callToolWithMeta("llm", "llm_chat_with_image", Map.of(
                        "system_prompt", systemPrompt,
                        "user_text", userText,
                        "image_base64", imageBase64
                ));
                LlmCallResult call = toLlmCallResult(result == null ? null : result.getText(),
                        result == null || result.getMetadata() == null ? null
                                : usageFromMcpJson(result.getMetadata().path("usage")),
                        start, null);
                telemetryService.recordLlmCall(call);
                if (llmCircuitBreaker != null) {
                    llmCircuitBreaker.onSuccess(LlmCircuitBreaker.CHANNEL_MULTIMODAL);
                }
                return call.getText();
            } catch (Exception e) {
                lastException = e;
                log.warn("LLM image call attempt {} failed: {}", attempt + 1, e.getMessage());
                if (!LlmRetryPolicy.isRetryable(e)) {
                    log.warn("[LLM] 多模态调用出现非可重试错误，停止重试: {}", e.getMessage());
                    break;
                }
            }
            if (attempt < maxRetries - 1) {
                try {
                    Thread.sleep(retryDelayMs(attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // v7.3(L2): 不可重试错误不计入多模态通道熔断
        if (llmCircuitBreaker != null && LlmRetryPolicy.isRetryable(lastException)) {
            llmCircuitBreaker.onFailure(LlmCircuitBreaker.CHANNEL_MULTIMODAL);
        }
        throw new BusinessException(50002,
                "LLM多模态调用失败（已尝试" + maxRetries + "次）: " + (lastException != null ? lastException.getMessage() : "unknown"),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private OpenAiChatOptions buildOptions(double temperature, boolean enableThinking) {
        // v6.0: OpenAI starter 不透传 enable_thinking；temperature/maxTokens 对齐旧版 MCP 请求。
        // v7.3(L8): maxTokens 由硬编码 16384 改为 llm.max-tokens 配置（默认不变）。
        log.debug("[LLM] thinking flag={} (Spring AI OpenAI starter advisory only)", enableThinking);
        return OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .streamUsage(true)
                .build();
    }

    private String extractText(ChatResponse response) {
        if (response == null) {
            return "";
        }
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null || generation.getOutput().getText() == null) {
            return "";
        }
        return generation.getOutput().getText();
    }

    private Usage usageFromMcpJson(JsonNode usage) {
        if (usage == null || usage.isMissingNode()) {
            return null;
        }
        return new SimpleUsage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("total_tokens").asInt(0));
    }

    private LlmCallResult toLlmCallResult(String text, Usage usage, long startMs, Long firstTokenMs) {
        LlmCallResult.LlmCallResultBuilder builder = LlmCallResult.builder()
                .text(text)
                .durationMs(System.currentTimeMillis() - startMs)
                .firstTokenMs(firstTokenMs);
        if (usage == null) {
            builder.promptTokens(0).completionTokens(0).totalTokens(0);
        } else {
            builder.promptTokens(usage.getPromptTokens() == null ? 0 : usage.getPromptTokens())
                    .completionTokens(usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens())
                    .totalTokens(usage.getTotalTokens() == null ? 0 : usage.getTotalTokens());
        }
        return builder.build();
    }

    /**
     * v2.6: chatJson 复用 chat() + JSON 解析。
     * v8.6.2(9.7): schemaName 非空时按 llm.schema.mode 灰度执行出参契约校验——
     * observe 仅告警放行；enforce 附缺失字段清单重试一次，仍失败抛 50002 降级。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> chatJson(String systemPrompt, String userPrompt, double temperature) {
        return chatJson(systemPrompt, userPrompt, temperature, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> chatJson(String systemPrompt, String userPrompt, double temperature,
                                        String schemaName) {
        String response = chat(systemPrompt, userPrompt, temperature);
        String json = extractJsonObject(response);
        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse LLM JSON response. Raw: {}", response, e);
            throw new BusinessException(50002,
                    "LLM返回JSON解析失败: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        if (schemaName == null || schemaValidator == null) {
            return parsed;
        }
        List<String> errors = schemaValidator.validateJson(json, schemaName);
        if (errors.isEmpty()) {
            return parsed;
        }
        schemaValidator.recordViolation(schemaName);
        log.warn("chatJson 契约校验未通过 (schema={}, mode={}): {}", schemaName, schemaValidator.isEnforce() ? "enforce" : "observe", errors);
        if (!schemaValidator.isEnforce()) {
            return parsed;
        }
        // enforce：附缺失字段清单重试一次
        String hint = "上次输出不符合结构契约。缺失或类型错误的字段：\n"
                + String.join("\n", errors)
                + "\n请严格按约定结构重新输出完整 JSON，不要输出其他文字。";
        String retryResponse = chat(systemPrompt, userPrompt + "\n\n" + hint, temperature);
        String retryJson = extractJsonObject(retryResponse);
        List<String> retryErrors = schemaValidator.validateJson(retryJson, schemaName);
        if (!retryErrors.isEmpty()) {
            throw new BusinessException(50002,
                    "LLM 输出不符合结构契约(" + schemaName + "): " + String.join("; ", retryErrors),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        try {
            return objectMapper.readValue(retryJson, Map.class);
        } catch (Exception e) {
            throw new BusinessException(50002,
                    "LLM返回JSON解析失败: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return "{}";
        }
        String trimmed = text.trim();

        if (trimmed.contains("```")) {
            int fenceStart = trimmed.indexOf("```");
            int contentStart = trimmed.indexOf("\n", fenceStart);
            if (contentStart != -1) {
                int fenceEnd = trimmed.indexOf("```", contentStart);
                if (fenceEnd != -1) {
                    return trimmed.substring(contentStart + 1, fenceEnd).trim();
                }
            }
        }

        // v8.6.2(9.7): 括号配平扫描替代首尾截取——逐段尝试 JSON 解析，取第一个可解析的配平段，
        // 说明文字含大括号（如 {格式要求}）时不再误取；全部失败回落旧首尾逻辑兜底
        int start = trimmed.indexOf('{');
        while (start != -1) {
            int end = matchingBrace(trimmed, start);
            if (end != -1) {
                String candidate = trimmed.substring(start, end + 1);
                if (parsesAsJsonObject(candidate)) {
                    return candidate;
                }
            }
            start = trimmed.indexOf('{', start + 1);
        }

        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }

        return trimmed;
    }

    // v8.6.2(9.7): 候选段是否为可解析的 JSON 对象（配平扫描的甄别步骤）
    private boolean parsesAsJsonObject(String candidate) {
        try {
            return objectMapper.readTree(candidate).isObject();
        } catch (Exception e) {
            return false;
        }
    }

    // v8.6.2(9.7): 从 openIdx 起字符串感知的括号配平扫描，返回配平 '}' 下标；无配平返回 -1
    private Integer matchingBrace(String s, int openIdx) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
            } else if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * v2.6: 测试 LLM 连通性。
     */
    public Map<String, Object> testConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", provider);
        result.put("model", model);
        result.put("springAiChatModel", chatModel != null);
        result.put("springAiEmbeddingModel", embeddingModel != null);
        try {
            String response = chat("You are a helpful assistant.", "Reply with the word: ok", 0.0);
            String preview = response.length() > 200 ? response.substring(0, 200) : response;
            result.put("response_preview", preview);
            result.put("status", "success");
        } catch (Exception e) {
            result.put("response_preview", "Error: " + e.getMessage());
            result.put("status", "failed");
        }
        return result;
    }

    // v6.1 (B 方案): 对 userPrompt 做统一长度约束，避免 277KB 巨型 prompt 触发 idle/read timeout。
    // 截断时保留头部并追加提示标记，让调用方仍能感知上下文被裁剪。
    private String boundPrompt(String userPrompt) {
        if (userPrompt == null || userPrompt.length() <= maxPromptChars) {
            return userPrompt;
        }
        // v8.4fix: 保头尾裁中段——任务指令/补齐 gaps 清单通常在 prompt 尾部，
        // 旧的纯头部截断会丢掉最关键的内容导致生成跑偏；尾部保留 1/4 预算（封顶 40k）
        int tailChars = Math.min(maxPromptChars / 4, 40000);
        int headChars = maxPromptChars - tailChars;
        log.error("[LLM] prompt 超限 {} → 保头 {} + 保尾 {} 裁中段（保险丝触发，上游预算可能失效）",
                userPrompt.length(), headChars, tailChars);
        String head = userPrompt.substring(0, headChars);
        String tail = userPrompt.substring(userPrompt.length() - tailChars);
        return head
                + "\n\n[system] 中段上下文已按 llm.max-prompt-chars 上限截断（原始长度 "
                + userPrompt.length() + "），仅保留头尾，请基于现有内容作答。\n\n"
                + tail;
    }

    // v6.5: 延迟 = 基数 * [0.8, 1.2) 抖动，避免多个任务同时重试再次撞限流
    private long retryDelayMs(int attempt) {
        int index = Math.min(attempt, RETRY_DELAYS_MS.length - 1);
        long base = RETRY_DELAYS_MS[index];
        double jitter = 0.8 + Math.random() * 0.4;
        return (long) (base * jitter);
    }

    /**
     * 最小 Usage 实现，用于 MCP 多模态返回（chatWithImage）的埋点适配。
     */
    private static final class SimpleUsage implements Usage {
        private final int promptTokens;
        private final int completionTokens;
        private final int totalTokens;

        private SimpleUsage(int promptTokens, int completionTokens, int totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Integer getTotalTokens() {
            return totalTokens;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }
}
