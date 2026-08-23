package com.testagent.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vT2: JSON 工具类基线测试。
 */
class JsonHelperTest {

    @Test
    void parseMapHandlesValidAndInvalid() {
        assertEquals(Map.of("a", "b"), JsonHelper.parseMap("{\"a\":\"b\"}"));
        assertTrue(JsonHelper.parseMap("not-json").isEmpty());
        assertTrue(JsonHelper.parseMap(null).isEmpty());
        assertTrue(JsonHelper.parseMap("").isEmpty());
    }

    @Test
    void parseListStringHandlesValidAndInvalid() {
        assertEquals(List.of("a", "b"), JsonHelper.parseListString("[\"a\",\"b\"]"));
        assertTrue(JsonHelper.parseListString("not-json").isEmpty());
        assertTrue(JsonHelper.parseListString(null).isEmpty());
    }

    @Test
    void parseListMapHandlesValidAndInvalid() {
        List<Map<String, Object>> result = JsonHelper.parseListMap("[{\"x\":1}]");
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).get("x"));
        assertTrue(JsonHelper.parseListMap("[]").isEmpty());
        assertTrue(JsonHelper.parseListMap("not-json").isEmpty());
    }

    /**
     * v7.11(T3): 空值/解析失败必须返回可变容器。
     * 背景：旧实现返回 Collections.emptyMap()/emptyList()（不可变），
     * 调用方（如 TestCaseReviewAgent）直接 put 补充字段会抛
     * UnsupportedOperationException 导致整条评审链路失败。
     */
    @Test
    void emptyFallbacksAreMutable() {
        for (String input : new String[]{null, "", "not-json"}) {
            Map<String, Object> map = JsonHelper.parseMap(input);
            assertDoesNotThrow(() -> map.put("extra", "v"));
            assertEquals("v", map.get("extra"));

            List<Map<String, Object>> listMap = JsonHelper.parseListMap(input);
            assertDoesNotThrow(() -> listMap.add(Map.of("k", "v")));
            assertEquals(1, listMap.size());

            List<String> listStr = JsonHelper.parseListString(input);
            assertDoesNotThrow(() -> listStr.add("item"));
            assertEquals(1, listStr.size());
        }
    }
}
