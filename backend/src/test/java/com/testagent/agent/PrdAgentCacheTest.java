package com.testagent.agent;

import com.testagent.common.BusinessException;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.service.LlmResultCacheService;
import com.testagent.service.LlmService;
import com.testagent.service.PromptSkillLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v7.5(A15): PRD 解析缓存——同输入不重复调 LLM（temp 0.2 漂移消除 + 省调用）、
 * 内容变化重新调、失败/非法响应不写缓存。
 */
class PrdAgentCacheTest {

    private static final String LLM_JSON = """
            {
              "modules": [{"name": "订单", "description": "订单模块"}],
              "requirements": [{"title": "创建订单", "description": "用户可创建订单", "priority": "P0"}],
              "businessRules": [], "stateFlows": [], "entities": []
            }
            """;

    private LlmService llmService;
    private LlmResultCacheService cacheService;
    private PrdAgent prdAgent;

    @BeforeEach
    void setUp() {
        llmService = mock(LlmService.class);
        cacheService = mock(LlmResultCacheService.class);
        PromptSkillLoader promptSkillLoader = mock(PromptSkillLoader.class);
        // load(name, fallback) 直接返回 fallback，保证缓存键与 LLM 调用用同一 prompt
        when(promptSkillLoader.load(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        prdAgent = new PrdAgent();
        ReflectionTestUtils.setField(prdAgent, "llmService", llmService);
        ReflectionTestUtils.setField(prdAgent, "promptSkillLoader", promptSkillLoader);
        ReflectionTestUtils.setField(prdAgent, "llmResultCacheService", cacheService);
    }

    private List<Map<String, Object>> prdDocs(String content) {
        return List.of(Map.of("title", "主PRD", "content", content, "docType", "prd"));
    }

    @Test
    void firstAnalysisCallsLlmAndWritesCache() {
        when(cacheService.get(eq("prd_analysis"), anyString(), anyString())).thenReturn(null);
        when(llmService.chatWithAnalysis(anyString(), anyString(), anyDouble())).thenReturn(LLM_JSON);

        PrdAnalysisResult result = prdAgent.analyze(prdDocs("用户可以创建订单"), List.of(), null);

        assertEquals(1, result.getRequirements().size());
        verify(llmService, times(1)).chatWithAnalysis(anyString(), anyString(), anyDouble());
        verify(cacheService, times(1)).put(eq("prd_analysis"), anyString(), anyString(), eq(LLM_JSON));
    }

    // 同输入第二次：缓存命中，不调 LLM，结果与首次完全一致（漂移消除）
    @Test
    void secondAnalysisWithSameInputHitsCacheWithoutLlm() {
        when(cacheService.get(eq("prd_analysis"), anyString(), anyString())).thenReturn(null, LLM_JSON);
        when(llmService.chatWithAnalysis(anyString(), anyString(), anyDouble())).thenReturn(LLM_JSON);

        PrdAnalysisResult first = prdAgent.analyze(prdDocs("用户可以创建订单"), List.of(), null);
        PrdAnalysisResult second = prdAgent.analyze(prdDocs("用户可以创建订单"), List.of(), null);

        verify(llmService, times(1)).chatWithAnalysis(anyString(), anyString(), anyDouble());
        verify(cacheService, times(1)).put(anyString(), anyString(), anyString(), anyString());
        assertEquals(first.getRequirements(), second.getRequirements());
        assertEquals(first.getModules(), second.getModules());
    }

    // PRD 内容修改一个字 → 键变化 → 缓存未命中 → 重新调 LLM
    @Test
    void prdContentChangeBypassesCache() {
        when(cacheService.get(eq("prd_analysis"), anyString(), anyString())).thenReturn(null);
        when(llmService.chatWithAnalysis(anyString(), anyString(), anyDouble())).thenReturn(LLM_JSON);

        prdAgent.analyze(prdDocs("用户可以创建订单"), List.of(), null);
        prdAgent.analyze(prdDocs("用户可以创建订单。"), List.of(), null);

        verify(llmService, times(2)).chatWithAnalysis(anyString(), anyString(), anyDouble());
    }

    // LLM 返回非法 JSON：抛 BusinessException 且不写缓存（防毒缓存）
    @Test
    void invalidLlmResponseThrowsAndNeverCaches() {
        when(cacheService.get(eq("prd_analysis"), anyString(), anyString())).thenReturn(null);
        when(llmService.chatWithAnalysis(anyString(), anyString(), anyDouble()))
                .thenReturn("not a json object");

        assertThrows(BusinessException.class,
                () -> prdAgent.analyze(prdDocs("用户可以创建订单"), List.of(), null));
        verify(cacheService, never()).put(anyString(), anyString(), anyString(), anyString());
    }

    // 缓存内容损坏（毒缓存）：解析失败自动落回 LLM 路径重新生成
    @Test
    void poisonedCacheFallsBackToLlm() {
        when(cacheService.get(eq("prd_analysis"), anyString(), anyString()))
                .thenReturn("broken cache entry");
        when(llmService.chatWithAnalysis(anyString(), anyString(), anyDouble())).thenReturn(LLM_JSON);

        PrdAnalysisResult result = prdAgent.analyze(prdDocs("用户可以创建订单"), List.of(), null);

        assertEquals(1, result.getRequirements().size());
        verify(llmService, times(1)).chatWithAnalysis(anyString(), anyString(), anyDouble());
    }
}
