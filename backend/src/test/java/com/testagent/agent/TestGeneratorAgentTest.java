package com.testagent.agent;

import com.testagent.analyzer.result.BackendResult;
import com.testagent.common.BusinessException;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.service.LlmService;
import com.testagent.service.PromptSkillLoader;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestGeneratorAgentTest {

    @Test
    void generationWithoutPrdIsRejected() {
        TestGeneratorAgent agent = new TestGeneratorAgent();
        ReflectionTestUtils.setField(agent, "llmService", mock(LlmService.class));
        ReflectionTestUtils.setField(agent, "testCaseReviewAgent", mock(TestCaseReviewAgent.class));
        ReflectionTestUtils.setField(agent, "promptSkillLoader", new PromptSkillLoader());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                agent.generate(null, List.of(), BackendResult.skipped(), null, null, null));

        assertEquals("请先添加 PRD 文档", ex.getMessage());
    }

    @Test
    void prdGenerationFailureDoesNotFallbackToCodeDriven() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyDouble()))
                .thenThrow(new RuntimeException("llm boom"));
        TestGeneratorAgent agent = new TestGeneratorAgent();
        ReflectionTestUtils.setField(agent, "llmService", llmService);
        ReflectionTestUtils.setField(agent, "testCaseReviewAgent", mock(TestCaseReviewAgent.class));
        ReflectionTestUtils.setField(agent, "promptSkillLoader", new PromptSkillLoader());

        PrdAnalysisResult prd = new PrdAnalysisResult();
        prd.setRequirements(List.of(Map.of(
                "title", "create order",
                "description", "user can create order")));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                agent.generate(prd, List.of(), BackendResult.skipped(), null, null, null));

        assertTrue(ex.getMessage().contains("PRD 生成用例失败"));
    }
}
