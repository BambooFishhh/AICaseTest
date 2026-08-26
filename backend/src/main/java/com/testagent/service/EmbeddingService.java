package com.testagent.service;

import com.testagent.common.LlmCircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * v5.4: embedding 服务。
 * v6.0: 从 MCP llm_embedding 改为 Spring AI EmbeddingModel。
 * 必须保证与 Milvus 集合维度一致（默认 1024）；若维度不一致需停手报告，不自动重建集合。
 * v8.8.1(10.3): embedding 通道独立熔断（channel=embedding，与 text/multimodal 隔离），
 * 主模型失败/熔断时切换 llm.models.fallback.embedding-* 降级端点（未配置则维持空向量降级语义）。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    // v8.8.1(10.3): 独立熔断通道名
    public static final String CHANNEL_EMBEDDING = "embedding";

    @Autowired
    private EmbeddingModel embeddingModel;

    // v7.14(E17): 失败日志带模型名——404（模型不存在）/401（密钥）一眼可判
    @Value("${spring.ai.openai.embedding.options.model:}")
    private String embeddingModelName;

    // v8.8.1(10.3): 独立熔断与降级模型——no-op/null 兜底保持直 new 单测行为
    private LlmCircuitBreaker circuitBreaker;

    @Autowired(required = false)
    void setCircuitBreaker(LlmCircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    private com.testagent.config.LlmProviders providers;

    @Autowired(required = false)
    void setProviders(com.testagent.config.LlmProviders providers) {
        this.providers = providers;
    }

    public boolean isConfigured() {
        return embeddingModel != null;
    }

    /**
     * v6.0: 当前 embedding 模型输出维度（用于校验 Milvus 集合维度）。
     */
    public int getDimensions() {
        try {
            return embeddingModel.dimensions();
        } catch (Exception e) {
            log.debug("Embedding model dimensions unavailable: {}", e.getMessage());
            return -1;
        }
    }

    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (!isConfigured()) {
            log.debug("EmbeddingModel unavailable, skip embedding");
            return List.of();
        }
        // v8.8.1(10.3): 主通道熔断打开 → 直接走降级端点（未配置则空向量）
        boolean breakerOpen = circuitBreaker != null && !circuitBreaker.allowRequest(CHANNEL_EMBEDDING);
        if (!breakerOpen) {
            try {
                List<Float> result = toVector(embeddingModel.embed(text));
                if (circuitBreaker != null) {
                    circuitBreaker.onSuccess(CHANNEL_EMBEDDING);
                }
                return result;
            } catch (Exception e) {
                // v7.14(E17): 带模型名诊断——404=模型不存在（端点/模型名错配），401=密钥问题
                log.warn("Embedding failed (model={}): {}", embeddingModelName, e.getMessage());
                if (circuitBreaker != null && isRetryable(e)) {
                    circuitBreaker.onFailure(CHANNEL_EMBEDDING);
                }
            }
        } else {
            log.debug("Embedding 熔断打开，直接尝试降级端点");
        }
        // v8.8.1(10.3): 降级端点兜底——未配置时保持既有空向量语义（结构判重兜底）
        EmbeddingModel fallback = providers == null ? null : providers.fallbackEmbeddingModel();
        if (fallback == null) {
            metricsFallbackMissed();
            return List.of();
        }
        try {
            List<Float> result = toVector(fallback.embed(text));
            log.info("Embedding 走降级端点成功");
            return result;
        } catch (Exception e) {
            log.warn("Embedding fallback failed: {}", e.getMessage());
            return List.of();
        }
    }

    private void metricsFallbackMissed() {
        // 降级未配置的空向量降级不计数（既有行为），此处留扩展点
    }

    private boolean isRetryable(Exception e) {
        // 4xx 配置类错误不计熔断（与文本通道口径一致）
        String msg = e.getMessage() == null ? "" : e.getMessage();
        return !(msg.contains("401") || msg.contains("403") || msg.contains("404"));
    }

    private List<Float> toVector(float[] vector) {
        List<Float> result = new ArrayList<>(vector.length);
        for (float value : vector) {
            result.add(value);
        }
        return result;
    }
}
