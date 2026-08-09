package com.testagent.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

// v1.10: PRD 解析结果
@Data
public class PrdAnalysisResult {

    private List<Map<String, Object>> modules;        // [{name, description}]
    private List<Map<String, Object>> requirements;   // [{title, description, acceptanceCriteria[], priority}]
    private List<Map<String, Object>> businessRules;  // [{rule, ruleType}]
    private List<Map<String, Object>> stateFlows;     // [{name, states[], transitions[]}]
    private List<String> entities;

    public boolean isEmpty() {
        return (modules == null || modules.isEmpty())
                && (requirements == null || requirements.isEmpty())
                && (businessRules == null || businessRules.isEmpty())
                && (stateFlows == null || stateFlows.isEmpty())
                && (entities == null || entities.isEmpty());
    }
}
