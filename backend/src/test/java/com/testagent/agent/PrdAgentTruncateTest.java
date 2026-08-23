package com.testagent.agent;

import com.testagent.common.BusinessException;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.service.LlmResultCacheService;
import com.testagent.service.LlmService;
import com.testagent.service.PromptSkillLoader;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v7.7: PRD 头尾截断（L4a）与 LLM 降级重试（A13）单测。
 */
class PrdAgentTruncateTest {

    private PrdAgent buildAgent(LlmService llmService) {
        PrdAgent agent = new PrdAgent();
        ReflectionTestUtils.setField(agent, "llmService", llmService);
        PromptSkillLoader promptSkillLoader = mock(PromptSkillLoader.class);
        when(promptSkillLoader.load(anyString(), anyString())).thenReturn("system prompt");
        ReflectionTestUtils.setField(agent, "promptSkillLoader", promptSkillLoader);
        ReflectionTestUtils.setField(agent, "llmResultCacheService", mock(LlmResultCacheService.class));
        return agent;
    }

    // ==================== L4a: truncateDoc 头尾保留 ====================

    @Test
    void truncateDocShortContentUnchanged() {
        PrdAgent agent = buildAgent(mock(LlmService.class));
        String shortDoc = "订单模块需求：下单、支付、退款";
        String result = ReflectionTestUtils.invokeMethod(agent, "truncateDoc", shortDoc);
        assertTrue(shortDoc.equals(result), "短文档应原样保留");
    }

    @Test
    void truncateDocLongContentKeepsHeadAndTail() {
        PrdAgent agent = buildAgent(mock(LlmService.class));
        // 头部 6000 字 + 中部 5000 字 + 尾部 6000 字（超过 MAX_PRD_LENGTH=12000）
        String head = "头".repeat(6000);
        String middle = "中".repeat(5000);
        String tail = "尾".repeat(6000);
        String longDoc = head + middle + tail;

        String result = ReflectionTestUtils.invokeMethod(agent, "truncateDoc", longDoc);

        // 头尾各保留一半（6000 字符），中部丢弃并明示省略字符数
        assertTrue(result.startsWith(head), "头部 6000 字符必须保留");
        assertTrue(result.endsWith(tail), "尾部 6000 字符必须保留（验收标准/边界条件所在）");
        assertTrue(result.contains("中略 5000 字符"), "省略量必须明示");
        assertTrue(!result.contains(middle), "中部应被丢弃");
    }

    @Test
    void truncateDocNullSafe() {
        PrdAgent agent = buildAgent(mock(LlmService.class));
        String result = ReflectionTestUtils.invokeMethod(agent, "truncateDoc", (Object) null);
        assertTrue("".equals(result));
    }

    // ==================== A13: 完整解析失败降级瘦身重试 ====================

    @Test
    void fullParseFailureFallsBackToSlimParse() {
        LlmService llmService = mock(LlmService.class);
        String slimJson = """
                {
                  "modules": [{"name": "订单", "description": "订单模块"}],
                  "requirements": [{"title": "用户可以创建订单", "description": "下单后扣库存",
                                     "acceptanceCriteria": ["下单成功"], "priority": "P0"}],
                  "businessRules": [{"rule": "库存不足禁止下单", "ruleType": "validation"}]
                }
                """;
        // 第一次（完整解析）返回截断垃圾，第二次（瘦身重试）返回核心三块
        when(llmService.chatWithAnalysis(anyString(), anyString(), anyDouble()))
                .thenReturn("{\"modules\":[{\"name\":\"订单截断")  // 输出被 maxTokens 截断
                .thenReturn(slimJson);

        PrdAgent agent = buildAgent(llmService);

        PrdAnalysisResult result = agent.analyze(List.of(Map.of(
                "content", "订单模块需求文档",
                "title", "主 PRD",
                "docType", "prd")), List.of(), null);

        assertNotNull(result);
        assertTrue(!result.isEmpty(), "瘦身解析结果应非空（降级生成好过整体阻断）");
        assertTrue(result.getRequirements().size() >= 1);
        assertTrue(result.getBusinessRules().size() >= 1);
    }

    @Test
    void slimParseAlsoFailureThrowsExplicitError() {
        LlmService llmService = mock(LlmService.class);
        // 两次都失败 → 抛出带原始长度提示的明确错误
        when(llmService.chatWithAnalysis(anyString(), anyString(), anyDouble()))
                .thenReturn("garbage")
                .thenReturn("also garbage");

        PrdAgent agent = buildAgent(llmService);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                agent.analyze(List.of(Map.of(
                        "content", "订单模块需求文档",
                        "title", "主 PRD",
                        "docType", "prd")), List.of(), null));
        assertTrue(ex.getMessage().contains("PRD 解析失败"));
        assertTrue(ex.getMessage().contains("截断"), "错误应提示输出可能被截断: " + ex.getMessage());
    }
}
