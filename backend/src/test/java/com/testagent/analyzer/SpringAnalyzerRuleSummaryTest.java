package com.testagent.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.result.BusinessRule;
import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.analyzer.result.EnumInfo;
import com.testagent.analyzer.result.EntityInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.13: buildRuleSummary 合法性收敛单测——旧实现 json.substring(0, 30000)
 * 会把 JSON 砍成非法 JSON 塞进 prompt。新实现先减条目再序列化，
 * 任何路径返回的都必须是可解析的合法 JSON 且长度 ≤ 上限。
 * 直接 new（不走容器）同时验证 @Value 字段初始化默认值兜底。
 */
class SpringAnalyzerRuleSummaryTest {

    private final SpringAnalyzer analyzer = new SpringAnalyzer();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private EndpointInfo endpoint(int i) {
        return EndpointInfo.builder()
                .method("POST")
                .path("/api/order/create/" + i)
                .function("OrderController.create" + i)
                .file("src/main/java/com/example/OrderController.java")
                .description("创建订单 " + i)
                .businessLogic("x".repeat(200))
                .build();
    }

    private String buildRuleSummary(List<EndpointInfo> endpoints) throws Exception {
        Method m = SpringAnalyzer.class.getDeclaredMethod("buildRuleSummary",
                List.class, List.class, List.class, List.class);
        m.setAccessible(true);
        return (String) m.invoke(analyzer, endpoints, List.of(), List.of(), List.of());
    }

    private void setRuleSummaryMaxChars(int value) throws Exception {
        Field f = SpringAnalyzer.class.getDeclaredField("ruleSummaryMaxChars");
        f.setAccessible(true);
        f.setInt(analyzer, value);
    }

    @Test
    void oversizedListStillProducesLegalJsonWithinLimit() throws Exception {
        // 500 条 endpoint × 200 字符 businessLogic ≈ 100k+ 字符——旧实现必超 80k 并砍出非法 JSON
        List<EndpointInfo> endpoints = IntStream.range(0, 500)
                .mapToObj(this::endpoint)
                .toList();

        String json = buildRuleSummary(endpoints);

        assertTrue(json.length() <= 80000, "返回长度应 ≤ 80000，实际 " + json.length());
        JsonNode root = objectMapper.readTree(json);
        assertNotNull(root.get("endpoints"), "合法 JSON 且含 endpoints 明细");
        assertTrue(root.get("endpoints").size() > 0, "收敛后仍应保留明细子集");
    }

    @Test
    void endpointCountAlwaysReflectsTrueTotal() throws Exception {
        // count 保真：明细被裁但总量真实，LLM 可感知"明细被裁剪"
        List<EndpointInfo> endpoints = IntStream.range(0, 500)
                .mapToObj(this::endpoint)
                .toList();

        JsonNode root = objectMapper.readTree(buildRuleSummary(endpoints));
        assertEquals(500, root.get("endpointCount").asInt(), "endpointCount 恒为真实总数");
    }

    @Test
    void smallListPassesThroughUnscaled() throws Exception {
        // 少量条目首轮即达标，返回完整明细
        List<EndpointInfo> endpoints = IntStream.range(0, 3)
                .mapToObj(this::endpoint)
                .toList();

        JsonNode root = objectMapper.readTree(buildRuleSummary(endpoints));
        assertEquals(3, root.get("endpoints").size(), "少量条目不降采样");
        assertEquals(3, root.get("endpointCount").asInt());
    }

    @Test
    void extremeSmallLimitFallsBackToCountsOnlySkeleton() throws Exception {
        // 上限压到 500：收敛 5 轮仍超，兜底 counts-only 骨架（仍是合法 JSON）
        setRuleSummaryMaxChars(500);
        List<EndpointInfo> endpoints = IntStream.range(0, 200)
                .mapToObj(this::endpoint)
                .toList();

        String json = buildRuleSummary(endpoints);

        assertTrue(json.length() <= 500, "骨架也应 ≤ 上限，实际 " + json.length());
        JsonNode root = objectMapper.readTree(json);
        assertEquals(200, root.get("endpointCount").asInt());
        assertNotNull(root.get("note"), "骨架带省略说明");
    }
}
