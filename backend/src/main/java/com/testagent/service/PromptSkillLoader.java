package com.testagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v5.13: 从 classpath skills/ 目录加载 Prompt 模板。
 * 文件缺失或读取失败时回退到调用方提供的内嵌 Prompt，保证行为不变。
 */
@Component
public class PromptSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptSkillLoader.class);
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String load(String name, String fallback) {
        return cache.computeIfAbsent(name, n -> read(n, fallback));
    }

    private String read(String name, String fallback) {
        try {
            ClassPathResource resource = new ClassPathResource("skills/" + name + ".md");
            if (!resource.exists()) {
                return fallback;
            }
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return content.isBlank() ? fallback : content;
        } catch (Exception e) {
            log.warn("Failed to load prompt skill {}: {}", name, e.getMessage());
            return fallback;
        }
    }
}
