package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.common.BusinessException;
import com.testagent.dto.SettingsDTO;
import com.testagent.dto.UpdateSettingsRequest;
import com.testagent.dto.GenerationParams;
import com.testagent.entity.SystemSetting;
import com.testagent.repository.SystemSettingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class SettingsService {

    @Autowired
    private SystemSettingRepository systemSettingRepository;

    @Autowired
    private LlmService llmService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Cacheable(value = "settings", key = "'llm'")
    public SettingsDTO getSettings() {
        return SettingsDTO.builder()
                .llmProvider(getSetting("llm_provider", ""))
                .llmModel(getSetting("llm_model", ""))
                .llmApiKey(getSetting("llm_api_key", ""))
                .llmBaseUrl(getSetting("llm_base_url", ""))
                .build();
    }

    @Transactional
    @CacheEvict(value = "settings", allEntries = true)
    public void updateSettings(UpdateSettingsRequest req) {
        if (req.getLlmProvider() != null) {
            saveSetting("llm_provider", req.getLlmProvider());
        }
        if (req.getLlmModel() != null) {
            saveSetting("llm_model", req.getLlmModel());
        }
        if (req.getLlmApiKey() != null) {
            saveSetting("llm_api_key", req.getLlmApiKey());
        }
        if (req.getLlmBaseUrl() != null) {
            saveSetting("llm_base_url", req.getLlmBaseUrl());
        }
    }

    public Map<String, Object> testLlm() {
        return llmService.testConnection();
    }

    // v3.17: 系统级默认生成参数（新建项目时作为初始值）
    @Cacheable(value = "settings", key = "'generationParams'")
    public GenerationParams getDefaultGenerationParams() {
        String json = getSetting("default_generation_params", "");
        if (json == null || json.isBlank()) {
            return GenerationParams.defaults();
        }
        try {
            GenerationParams params = objectMapper.readValue(json, GenerationParams.class);
            if (params.getCaseDensity() == null) params.setCaseDensity("medium");
            if (params.getTemperature() == null) params.setTemperature(0.4);
            if (params.getFocusTypes() == null) params.setFocusTypes(java.util.List.of());
            return params;
        } catch (Exception e) {
            return GenerationParams.defaults();
        }
    }

    @Transactional
    @CacheEvict(value = "settings", key = "'generationParams'")
    public GenerationParams updateDefaultGenerationParams(GenerationParams params) {
        if (params.getCaseDensity() == null) params.setCaseDensity("medium");
        if (params.getTemperature() == null) params.setTemperature(0.4);
        if (params.getFocusTypes() == null) params.setFocusTypes(java.util.List.of());
        // v5.13: 默认执行 URL 不再由生成参数界面维护，保存时保留历史值
        if (params.getDefaultTargetUrl() == null || params.getDefaultTargetUrl().isBlank()) {
            try {
                String json = getSetting("default_generation_params", "");
                if (json != null && !json.isBlank()) {
                    GenerationParams existing = objectMapper.readValue(json, GenerationParams.class);
                    if (existing.getDefaultTargetUrl() != null && !existing.getDefaultTargetUrl().isBlank()) {
                        params.setDefaultTargetUrl(existing.getDefaultTargetUrl());
                    }
                }
            } catch (Exception e) {
                // 忽略历史值解析失败，保持空值
            }
        }
        try {
            saveSetting("default_generation_params", objectMapper.writeValueAsString(params));
        } catch (Exception e) {
            throw new BusinessException(50011, "保存默认生成参数失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return params;
    }

    private String getSetting(String key, String defaultValue) {
        return systemSettingRepository.findById(key)
                .map(SystemSetting::getSettingValue)
                .orElse(defaultValue);
    }

    private void saveSetting(String key, String value) {
        SystemSetting setting = systemSettingRepository.findById(key)
                .orElseGet(() -> {
                    SystemSetting s = new SystemSetting();
                    s.setSettingKey(key);
                    return s;
                });
        setting.setSettingValue(value);
        setting.setUpdatedAt(LocalDateTime.now());
        systemSettingRepository.save(setting);
    }
}
