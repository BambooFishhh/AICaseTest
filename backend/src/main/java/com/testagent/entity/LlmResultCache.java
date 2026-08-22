package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * v7.5(A11/A15): LLM 结果缓存——按 prompt hash 键缓存 LLM 原始响应，
 * 同输入（模型+systemPrompt+userPrompt）不重复调 LLM。
 * 键含模型名与完整 prompt：内容/prompt/模型任一变化自然失效，无 TTL。
 */
@Entity
@Table(name = "llm_result_cache")
@Data
@NoArgsConstructor
public class LlmResultCache {

    @Id
    @Column(name = "cache_key", length = 64)
    private String cacheKey;

    @Column(name = "cache_kind", length = 32)
    private String cacheKind;

    @Column(name = "result_text", columnDefinition = "MEDIUMTEXT")
    private String resultText;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public LlmResultCache(String cacheKey, String cacheKind, String resultText) {
        this.cacheKey = cacheKey;
        this.cacheKind = cacheKind;
        this.resultText = resultText;
        this.createdAt = LocalDateTime.now();
    }
}
