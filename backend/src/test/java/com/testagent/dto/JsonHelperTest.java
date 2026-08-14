package com.testagent.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
}
