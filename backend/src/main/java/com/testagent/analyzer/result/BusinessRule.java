package com.testagent.analyzer.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessRule {

    private String file;

    private String function;

    private String rule;

    private String ruleType;

    private List<String> sources;

    public Map<String, Object> toContextMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("file", file);
        map.put("function", function);
        map.put("rule", rule);
        map.put("ruleType", ruleType);
        map.put("sources", sources == null ? List.of() : sources);
        return map;
    }
}
