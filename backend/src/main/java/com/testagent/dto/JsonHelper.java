package com.testagent.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsonHelper {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> LIST_STRING_TYPE = new TypeReference<>() {};

    private JsonHelper() {
    }

    // v7.11(T3): 空值/解析失败一律返回可变容器——调用方（如 TestCaseReviewAgent）
    // 会直接对返回值 put 补充字段，不可变 Collections.emptyMap() 会抛 UnsupportedOperationException

    public static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> result = mapper.readValue(json, MAP_TYPE);
            return result != null ? result : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public static List<Map<String, Object>> parseListMap(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> result = mapper.readValue(json, LIST_MAP_TYPE);
            return result != null ? result : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static List<String> parseListString(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> result = mapper.readValue(json, LIST_STRING_TYPE);
            return result != null ? result : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
