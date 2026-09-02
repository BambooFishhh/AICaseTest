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

    // ==================== v9.8: 商品 id 真实性归一（幻觉 id → 默认有效 id） ====================

    @Test
    void hallucinatedGoodsIdReplacedInTargetAndExpectation() {
        // 实测回归：LLM 幻觉 /goods/456 /goods/789 → 详情页 500 + 孤儿足迹拖垮足迹接口
        TestGeneratorAgent agent = new TestGeneratorAgent();
        String steps = """
                [{"order":1,"type":"ui_action","action":"打开商品详情页","target":"/goods/456",
                  "uiSelector":{"type":"route","value":"/goods/456"}},
                 {"order":2,"type":"ui_action","action":"打开商品详情页","target":"/goods/1006002",
                  "uiSelector":{"type":"route","value":"/goods/1006002"}},
                 {"order":3,"type":"ui_action","action":"打开商品详情页","target":"/goods/789",
                  "uiSelector":{"type":"route","value":"/goods/789"}},
                 {"order":4,"type":"state_assert","action":"验证取消收藏","target":"收藏列表",
                  "expected":"列表中不包含ID为789的商品"}]""";
        String cleaned = agent.normalizeGoodsIdsInSteps(steps);
        assertTrue(cleaned.contains("/goods/1006002"), "幻觉 id 应替换为默认有效 id: " + cleaned);
        assertTrue(!cleaned.contains("/goods/456") && !cleaned.contains("/goods/789"),
                "不应残留幻觉 id: " + cleaned);
        assertTrue(cleaned.contains("ID 为 1006002"), "期望文本中的 ID 引用应同步替换: " + cleaned);
        // 原有效 id 保留(target+value 各 1) + 两个幻觉 id 各替换出 target+value 2 处 = 6
        assertEquals(6, countOccurrences(cleaned, "/goods/1006002"), "有效 id 应保留: " + cleaned);
    }

    @Test
    void validGoodsIdsUntouchedByNormalization() {
        TestGeneratorAgent agent = new TestGeneratorAgent();
        String steps = "[{\"order\":1,\"type\":\"ui_action\",\"action\":\"打开商品详情页\","
                + "\"target\":\"/goods/1116011\",\"uiSelector\":{\"type\":\"route\",\"value\":\"/goods/1116011\"}}]";
        assertEquals(steps, agent.normalizeGoodsIdsInSteps(steps), "有效 id 不应被改动");
    }

    @Test
    void jqueryPseudoCssSelectorRejected() {
        // 实测回归：'css=.order-stat .item:contains('待付款')' 是 jQuery 写法，
        // Playwright querySelectorAll 抛 SyntaxError 必炸，固化的 css 选择器必须剔除；
        // 合法 text 选择器不受影响
        TestGeneratorAgent agent = new TestGeneratorAgent();
        String steps = "[{\"order\":1,\"type\":\"ui_action\",\"action\":\"点击订单统计项\",\"target\":\"待付款\","
                + "\"uiSelector\":{\"type\":\"css\",\"value\":\".order-stat .item:contains('待付款')\"}},"
                + "{\"order\":2,\"type\":\"ui_action\",\"action\":\"打开收藏页\",\"target\":\"/collect\","
                + "\"uiSelector\":{\"type\":\"text\",\"value\":\"我的收藏\"}}]";
        String cleaned = agent.sanitizeUiSelectors(steps);
        assertTrue(!cleaned.contains(":contains"), "jQuery 伪选择器应被剔除: " + cleaned);
        assertTrue(cleaned.contains("\"type\":\"text\""), "合法 text 选择器应保留: " + cleaned);
    }

    private static int countOccurrences(String hay, String needle) {
        int count = 0, idx = 0;
        while ((idx = hay.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private TestCase caseWithTitle(String title) {
        TestCase tc = new TestCase();
        tc.setTitle(title);
        return tc;
    }
}
