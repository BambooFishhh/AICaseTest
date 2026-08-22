-- v7.5(A11/A15): LLM 结果缓存表——按 prompt hash 键缓存 LLM 原始响应，
-- 同输入（模型+systemPrompt+userPrompt）不重复调 LLM；键含模型名与完整 prompt，
-- 内容/prompt/模型任一变化自然失效，无 TTL（旧键为无害垃圾，索引便于后续清理）。
CREATE TABLE llm_result_cache (
    cache_key VARCHAR(64) NOT NULL,
    cache_kind VARCHAR(32) NOT NULL,
    result_text MEDIUMTEXT,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (cache_key),
    KEY idx_llm_result_cache_kind (cache_kind, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
