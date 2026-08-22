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

    // v5.4: RAG 检索到的相似上下文（代码分析/PRD 语义片段）
    private List<String> ragContexts;

    // v5.10/v5.11: 用户其他上下文信息（补充需求）与多篇文档（注入 LLM 生成上下文）
    private String otherContextInfo;
    private List<Map<String, Object>> prdDocs;
    private List<Map<String, Object>> contextDocs;

    // v6.1 (前端 Agentic RAG): 按需求检索命中的 Top-K 前端组件语义摘要
    private List<Map<String, Object>> frontendComponents;

    // v6.4: 历史执行失败经验（RRF 检索 Top-K，生成时注入避免重复失败路径）
    private List<String> ragFailures;

    public boolean isEmpty() {
        return (modules == null || modules.isEmpty())
                && (requirements == null || requirements.isEmpty())
                && (businessRules == null || businessRules.isEmpty())
                && (stateFlows == null || stateFlows.isEmpty())
                && (entities == null || entities.isEmpty());
    }
}
