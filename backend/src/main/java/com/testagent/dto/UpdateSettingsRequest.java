package com.testagent.dto;

import lombok.Data;

@Data
public class UpdateSettingsRequest {

    private String llmProvider;

    private String llmModel;

    private String llmApiKey;

    private String llmBaseUrl;
}
