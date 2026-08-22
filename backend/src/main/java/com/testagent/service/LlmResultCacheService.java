package com.testagent.service;

import com.testagent.entity.LlmResultCache;
import com.testagent.repository.LlmResultCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * v7.5(A11/A15): LLM 结果缓存——按 prompt hash 键缓存 LLM 原始响应。
 *
 * 键 = SHA-256(模型名 + '\u0001' + systemPrompt + '\u0001' + userPrompt)：
 * 换模型 / prompt 模板演进 / 输入内容变化任一发生即新键自然失效，无 TTL。
 *
 * 降级语义：缓存 DB 任何异常只记日志——get 返回 null（未命中）、put 静默跳过，
 * 均落回直调 LLM 路径，绝不阻断分析/生成。
 */
@Service
public class LlmResultCacheService {

    private static final Logger log = LoggerFactory.getLogger(LlmResultCacheService.class);

    @Autowired
    private LlmResultCacheRepository repository;

    // 与 LlmService 同源配置——换模型自动全量失效
    @Value("${llm.model:gpt-4o}")
    private String model;

    /**
     * 查缓存。命中返回 LLM 原始响应文本；未命中/DB 异常返回 null（落回直调 LLM）。
     */
    public String get(String kind, String systemPrompt, String userPrompt) {
        try {
            return repository.findById(cacheKey(systemPrompt, userPrompt))
                    .filter(c -> kind.equals(c.getCacheKind()))
                    .map(LlmResultCache::getResultText)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("LLM result cache read failed (kind={}), fallback to direct LLM call: {}",
                    kind, e.getMessage());
            return null;
        }
    }

    /**
     * 写缓存。upsert 语义；并发主键冲突（两写者内容相同）静默忽略；DB 异常只记日志。
     */
    public void put(String kind, String systemPrompt, String userPrompt, String response) {
        if (response == null || response.isBlank()) {
            return;
        }
        try {
            String key = cacheKey(systemPrompt, userPrompt);
            LlmResultCache entry = repository.findById(key).orElseGet(() -> new LlmResultCache(key, kind, response));
            entry.setCacheKind(kind);
            entry.setResultText(response);
            repository.save(entry);
        } catch (DataIntegrityViolationException e) {
            // 并发竞争：另一写者已写入，内容相同，忽略
            log.debug("LLM result cache concurrent write (kind={}), ignored", kind);
        } catch (Exception e) {
            log.warn("LLM result cache write failed (kind={}): {}", kind, e.getMessage());
        }
    }

    private String cacheKey(String systemPrompt, String userPrompt) {
        String material = model + "\u0001" + nullToEmpty(systemPrompt) + "\u0001" + nullToEmpty(userPrompt);
        return sha256(material);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
