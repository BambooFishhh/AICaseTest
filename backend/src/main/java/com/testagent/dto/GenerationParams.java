package com.testagent.dto;

import lombok.Data;
import java.util.List;

/**
 * v3.4: 项目级生成参数。存储于 Project.settings JSON 的 generationParams 字段。
 * caseDensity 控制数量引导、temperature 控制 LLM 创造性、focusTypes 控制聚焦类型。
 */
@Data
public class GenerationParams {
    private String caseDensity = "medium";   // low/medium/high
    private Double temperature = 0.4;        // 0.2~0.6
    private List<String> focusTypes;         // positive/negative/boundary/data 子集，空=全部
    // v3.12: 项目默认执行 URL（可为空）
    private String defaultTargetUrl;
    // v13.16(D): 生成输入来源模式。code+prd(默认) / prd-only(纯 PRD,不注入代码状态机/接口)。
    private String sourceMode = "code+prd";

    /** 兜底默认值（JSON 解析失败或字段缺失时） */
    public static GenerationParams defaults() {
        GenerationParams p = new GenerationParams();
        p.setCaseDensity("medium");
        p.setTemperature(0.4);
        p.setFocusTypes(List.of());
        p.setDefaultTargetUrl(null);
        p.setSourceMode("code+prd");
        return p;
    }
}
