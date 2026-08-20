package com.testagent.analyzer.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v6.1 (SAINT 风格): 后端操作依赖图节点。operation 形如 ClassName.method，
 * dependsOn 说明该操作运行时依赖了哪些被调方法（同/跨 Service）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperationDep {

    private String operation;

    private String kind;

    private String file;

    private String description;

    private List<String> dependsOn;

    public Map<String, Object> toContextMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("operation", operation);
        map.put("kind", kind == null ? "service" : kind);
        map.put("file", file);
        map.put("description", description == null ? "" : description);
        map.put("dependsOn", dependsOn == null ? List.of() : dependsOn);
        return map;
    }
}
