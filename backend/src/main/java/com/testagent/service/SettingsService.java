package com.testagent.service;

import com.testagent.dto.SettingsDTO;
import com.testagent.dto.UpdateSettingsRequest;
import com.testagent.entity.SystemSetting;
import com.testagent.repository.SystemSettingRepository;
import org.springframework.beans.factory.annotation.Autowired;
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

    public SettingsDTO getSettings() {
        return SettingsDTO.builder()
                .llmProvider(getSetting("llm_provider", ""))
                .llmModel(getSetting("llm_model", ""))
                .llmApiKey(getSetting("llm_api_key", ""))
                .llmBaseUrl(getSetting("llm_base_url", ""))
                .build();
    }

    @Transactional
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
