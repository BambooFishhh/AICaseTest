package com.testagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// v5.14: 单次 LLM 调用的耗时/token/首 token 埋点结果
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmCallResult {

    private String text;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Long firstTokenMs;

    private Long durationMs;
}
