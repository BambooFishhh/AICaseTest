package com.testagent.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SettingsDTO {

    private String llmProvider;

    private String llmModel;

    private String llmApiKey;

    private String llmBaseUrl;

    public String getLlmApiKey() {
        if (llmApiKey == null || llmApiKey.isBlank()) {
            return null;
        }
        if (llmApiKey.length() <= 8) {
            return llmApiKey + "***";
        }
        return llmApiKey.substring(0, 8) + "***";
    }
}
