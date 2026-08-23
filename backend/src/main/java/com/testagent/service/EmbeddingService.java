package com.testagent.service;

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
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    @Autowired
    private EmbeddingModel embeddingModel;

    // v7.14(E17): 失败日志带模型名——404（模型不存在）/401（密钥）一眼可判
    @Value("${spring.ai.openai.embedding.options.model:}")
    private String embeddingModelName;

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
        try {
            float[] vector = embeddingModel.embed(text);
            List<Float> result = new ArrayList<>(vector.length);
            for (float value : vector) {
                result.add(value);
            }
            return result;
        } catch (Exception e) {
            // v7.14(E17): 带模型名诊断——404=模型不存在（端点/模型名错配），401=密钥问题
            log.warn("Embedding failed (model={}): {}", embeddingModelName, e.getMessage());
            return List.of();
        }
    }
}
