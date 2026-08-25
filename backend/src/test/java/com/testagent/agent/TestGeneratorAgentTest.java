package com.testagent.agent;

import com.testagent.analyzer.result.BackendResult;
import com.testagent.common.BusinessException;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.entity.TestCase;
import com.testagent.service.LlmService;
import com.testagent.service.PromptSkillLoader;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
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

    @Test
    void wrapPushDedupCollapsesInvisibleWhitespaceTitleVariants() {
        // v8.3fix: LLM 标题常带不可见空白变体（中间空格/NBSP/零宽），
        // 旧键 trim+lowercase 放行导致流式同题堆卡；新键剥离全部空白字符
        List<String> pushed = new ArrayList<>();
        TestGeneratorAgent.CaseCallback cb = TestGeneratorAgent.wrapPushDedup(tc ->
                pushed.add(tc.getTitle()));

        cb.onCase(caseWithTitle("购物车-添加商品到购物车"));
        cb.onCase(caseWithTitle("购物车- 添加商品到购物车"));
        cb.onCase(caseWithTitle("购物车-添加商品到购物车\u00A0"));
        cb.onCase(caseWithTitle("购物车-添加商品到购物车\u200B"));
        cb.onCase(caseWithTitle("购物车-添加商品到购物车\u3000"));

        assertEquals(1, pushed.size(), "不可见空白变体应视为同一标题，只推送首次");
        assertEquals("购物车-添加商品到购物车", pushed.get(0));
    }

    @Test
    void dedupTitleKeyIsCaseInsensitiveAndWhitespaceAgnostic() {
        assertEquals(
                TestGeneratorAgent.dedupTitleKey(caseWithTitle("  Add  商品 \u00A0到 购物车 ")),
                TestGeneratorAgent.dedupTitleKey(caseWithTitle("add商品到购物车")));
    }

    private TestCase caseWithTitle(String title) {
        TestCase tc = new TestCase();
        tc.setTitle(title);
        return tc;
    }
}
