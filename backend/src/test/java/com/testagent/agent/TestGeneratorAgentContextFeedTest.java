package com.testagent.agent;

import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.BusinessRule;
import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.dto.PrdAnalysisResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.7: 上下文精准投喂工具方法单测（G16/G17/G4/G10）。
 * 覆盖 token 重叠打分、RAG 切片标题提取、相似度上限、缺口截断、
 * prompt 端点截断、需求关键词汇集等纯函数逻辑。
 */
class TestGeneratorAgentContextFeedTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    // ==================== G16/G17/G4: scoreTextOverlap ====================

    @Test
    void scoreTextOverlapChinesePhraseMatchesBothDirections() {
        // 中文无空格整句成 token：一方 token 是另一方子串即命中（对称取最大）
        int forward = invokeScore("删除订单", "删除订单后进入回收站");
        assertTrue(forward > 0, "短句是长句子串应得正分");
        int backward = invokeScore("删除订单后进入回收站", "删除订单");
        assertEquals(forward, backward, "对称设计双向得分一致");
    }

    @Test
    void scoreTextOverlapEnglishTokenCaseInsensitive() {
        int score = invokeScore("Order Create", "ORDER creation flow");
        assertTrue(score > 0, "英文 token 大小写不敏感应命中");
    }

    @Test
    void scoreTextOverlapDisjointTextsScoreZero() {
        assertEquals(0, invokeScore("登录模块", "库存盘点"));
        assertEquals(0, invokeScore(null, "任意"));
        assertEquals(0, invokeScore("", "任意"));
    }

    private int invokeScore(String a, String b) {
        Integer result = ReflectionTestUtils.invokeMethod(agent, "scoreTextOverlap", a, b);
        return result == null ? 0 : result;
    }

    // ==================== G16: extractRagTitle ====================

    @Test
    void ragTitleStripsMarkdownPrefixAndCapsLength() {
        assertEquals("退款流程说明", invokeRagTitle("## 退款流程说明\n退款在 7 个工作日内到账"));
        assertEquals("好评率统计", invokeRagTitle("- 好评率统计"));
        // 超过 60 字符截断
        String longTitle = "超".repeat(80);
        assertEquals(60, invokeRagTitle(longTitle).length());
        // 空白切片返回空串
        assertEquals("", invokeRagTitle("  \n  "));
        assertEquals("", invokeRagTitle(null));
    }

    private String invokeRagTitle(String slice) {
        return ReflectionTestUtils.invokeMethod(agent, "extractRagTitle", slice);
    }

    // ==================== G16: maxSimilarityScore ====================

    @Test
    void maxSimilarityScoreTakesBestAcrossRequirements() {
        List<Map<String, Object>> requirements = new ArrayList<>();
        requirements.add(Map.of("title", "用户登录", "description", "账号密码登录"));
        requirements.add(Map.of("title", "删除订单", "description", "删除后进入回收站"));

        int score = ReflectionTestUtils.invokeMethod(agent, "maxSimilarityScore",
                "删除订单", requirements);
        assertTrue(score > 0, "与第二条需求重叠应得正分");
    }

    // ==================== G10: capIdsInto ====================

    @Test
    void capIdsIntoUnderLimitKeepsAllAndReturnsFalse() {
        Map<String, Object> gaps = new LinkedHashMap<>();
        List<Map<String, Object>> items = List.of(
                Map.of("id", "a"), Map.of("id", "b"));
        boolean truncated = ReflectionTestUtils.invokeMethod(agent, "capIdsInto",
                gaps, "requirementIds", items, 40);
        assertFalse(truncated);
        assertEquals(List.of("a", "b"), gaps.get("requirementIds"));
    }

    @Test
    void capIdsIntoOverLimitTruncatesAndReturnsTrue() {
        Map<String, Object> gaps = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            items.add(Map.of("id", "req-" + i));
        }
        boolean truncated = ReflectionTestUtils.invokeMethod(agent, "capIdsInto",
                gaps, "requirementIds", items, 40);
        assertTrue(truncated);
        assertInstanceOf(List.class, gaps.get("requirementIds"));
        assertEquals(40, ((List<?>) gaps.get("requirementIds")).size());
        assertEquals("req-39", ((List<?>) gaps.get("requirementIds")).get(39));
    }

    // ==================== G10: capChecklistForPrompt ====================

    @Test
    @SuppressWarnings("unchecked")
    void checklistEndpointsOver150TruncatedWithNote() {
        // v8.4: 生产默认已放宽到 250，此处显式钉住 150 验证截断说明条目机制本身
        ReflectionTestUtils.setField(agent, "checklistEndpointsCap", 150);
        List<Object> endpoints = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            endpoints.add(Map.of("id", "ep-" + i, "path", "/api/e" + i));
        }
        Map<String, Object> checklist = new LinkedHashMap<>();
        checklist.put("endpoints", endpoints);
        checklist.put("requirements", List.of(Map.of("id", "req-1")));

        Map<String, Object> capped = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                agent, "capChecklistForPrompt", checklist);
        List<Object> eps = (List<Object>) capped.get("endpoints");
        assertEquals(151, eps.size(), "前 150 条 + 1 条截断说明");
        Map<String, Object> note = (Map<String, Object>) eps.get(150);
        assertEquals("endpoints-truncated", note.get("id"));
        // 非本清单的其他键不受影响
        assertEquals(1, ((List<?>) capped.get("requirements")).size());
    }

    @Test
    void checklistUnder150Unchanged() {
        Map<String, Object> checklist = new LinkedHashMap<>();
        checklist.put("endpoints", List.of(Map.of("id", "ep-1")));
        Object result = ReflectionTestUtils.invokeMethod(agent, "capChecklistForPrompt", checklist);
        assertEquals(checklist, result);
    }

    // ==================== G17: requirementKeywords ====================

    @Test
    void requirementKeywordsMergeRequirementsAndRagWithContext() {
        PrdAnalysisResult prd = new PrdAnalysisResult();
        prd.setRequirements(List.of(
                Map.of("title", "用户登录", "description", "密码错误提示失败")));
        prd.setRagContexts(List.of("优惠券发放规则说明"));

        List<String> keywords = ReflectionTestUtils.invokeMethod(agent, "requirementKeywords", prd);
        assertEquals(2, keywords.size());
        assertTrue(keywords.get(0).contains("用户登录"));
        assertTrue(keywords.get(1).contains("优惠券"));
    }

    @Test
    void requirementKeywordsCapsAt60EntriesAnd100Chars() {
        List<Map<String, Object>> requirements = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            requirements.add(Map.of("title", "需求" + i, "description", "描述" + i));
        }
        PrdAnalysisResult prd = new PrdAnalysisResult();
        prd.setRequirements(requirements);
        prd.setRagContexts(List.of("本切片不应进入关键词集合"));

        List<String> keywords = ReflectionTestUtils.invokeMethod(agent, "requirementKeywords", prd);
        assertEquals(60, keywords.size(), "上限 60 条");
        assertTrue(keywords.stream().noneMatch(k -> k.contains("本切片")),
                "超限后 ragContexts 不再进入");

        // 单条超 100 字符截断
        PrdAnalysisResult single = new PrdAnalysisResult();
        single.setRequirements(List.of(Map.of("title", "超长需求", "description", "长".repeat(200))));
        List<String> capped = ReflectionTestUtils.invokeMethod(agent, "requirementKeywords", single);
        assertEquals(1, capped.size());
        assertTrue(capped.get(0).length() <= 100, "单条截到 100 字符");
    }

    // ==================== G17: endpointText / ruleText ====================

    @Test
    void endpointTextConcatenatesAllSignals() {
        EndpointInfo ep = EndpointInfo.builder()
                .path("/api/orders")
                .function("OrderController.create")
                .description("创建订单")
                .validation(List.of("amount must be positive"))
                .build();
        String text = ReflectionTestUtils.invokeMethod(agent, "endpointText", ep);
        assertTrue(text.contains("/api/orders"));
        assertTrue(text.contains("OrderController.create"));
        assertTrue(text.contains("创建订单"));
        assertTrue(text.contains("amount must be positive"));
    }

    @Test
    void ruleTextConcatenatesRuleAndType() {
        BusinessRule br = BusinessRule.builder()
                .rule("订单金额必须为正")
                .ruleType("validation")
                .build();
        String text = ReflectionTestUtils.invokeMethod(agent, "ruleText", br);
        assertTrue(text.contains("订单金额必须为正"));
        assertTrue(text.contains("validation"));
    }

    // ==================== G17: 过滤保底行为（空命中不过滤） ====================

    @Test
    void backendFilteringKeepsAllWhenNoOverlap() {
        // G17 的保底语义：关键词与所有 endpoint 均无重叠时保留全量，
        // 这里通过 scoreTextOverlap 全 0 的行为间接验证该保底的前提成立
        assertEquals(0, invokeScore("登录模块", "/api/inventory盘点 inventory check"));
    }

    // ==================== 便捷构造（未直接断言，保持编译引用真实 DTO） ====================

    @Test
    void backendResultSkippedFactoryUsable() {
        // 引用 BackendResult.skipped() 确认测试编译期与生成管线使用同一工厂
        BackendResult result = BackendResult.skipped();
        assertTrue(result.getEndpoints() == null || result.getEndpoints().isEmpty());
    }
}
