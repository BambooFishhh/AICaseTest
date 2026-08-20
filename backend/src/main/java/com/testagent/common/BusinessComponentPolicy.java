package com.testagent.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * v6.6: 统一“业务组件”判定口径，供 VueAnalyzer 与 TestGeneratorAgent 共用。
 *
 * 两类语义不同、阈值可配置：
 *  - needsLlmSummary：是否需要 LLM 摘要（阈值较高，默认 0.3）
 *  - inCoverage：是否进入覆盖清单（要求严格大于 0，0 分与负分通用组件不进入）
 *
 * businessScore 缺失、非数字或解析失败时，统一判定为“非业务组件”，
 * 避免字段异常把通用组件误判进生成链路。
 */
@Component
public class BusinessComponentPolicy {

    private final double llmSummaryThreshold;
    private final double coverageThreshold;

    public BusinessComponentPolicy(
            @Value("${app.business.llm-summary-threshold:0.3}") double llmSummaryThreshold,
            @Value("${app.business.coverage-threshold:0.0}") double coverageThreshold) {
        this.llmSummaryThreshold = llmSummaryThreshold;
        this.coverageThreshold = coverageThreshold;
    }

    /**
     * 是否需要 LLM 摘要。解析失败/缺失 -> false。
     */
    public boolean needsLlmSummary(Map<String, Object> component) {
        Double score = parse(component);
        return score != null && score >= llmSummaryThreshold;
    }

    /**
     * 是否进入覆盖清单。要求严格大于 coverageThreshold（默认 0），
     * 0 分通用组件与解析失败的组件一律排除。
     */
    public boolean inCoverage(Map<String, Object> component) {
        Double score = parse(component);
        return score != null && score > coverageThreshold;
    }

    private Double parse(Map<String, Object> component) {
        Object score = component == null ? null : component.get("businessScore");
        if (score == null) {
            return null;
        }
        if (score instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(score).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
