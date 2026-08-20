package com.testagent.analyzer.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EndpointInfo {

    private String method;

    private String path;

    private String function;

    private String file;

    private String description;

    private List<Map<String, Object>> parameters;

    private String requestBody;

    // v6.1 (SAINT): 响应结构与业务逻辑描述、可能抛出的异常类型
    private String responseBody;

    private String businessLogic;

    private List<String> exceptions;

    private List<String> permissions;

    private List<String> validation;

    private List<String> sources;

    public Map<String, Object> toContextMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("method", method);
        map.put("path", path);
        map.put("function", function);
        map.put("file", file);
        map.put("description", description != null && !description.isBlank() ? description : function);
        map.put("parameters", parameters == null ? List.of() : parameters);
        map.put("requestBody", requestBody == null ? "" : requestBody);
        map.put("responseBody", responseBody == null ? "" : responseBody);
        map.put("businessLogic", businessLogic == null ? "" : businessLogic);
        map.put("exceptions", exceptions == null ? List.of() : exceptions);
        map.put("permissions", permissions == null ? List.of() : permissions);
        map.put("validation", validation == null ? List.of() : validation);
        map.put("sources", sources == null ? List.of() : sources);
        return map;
    }
}
