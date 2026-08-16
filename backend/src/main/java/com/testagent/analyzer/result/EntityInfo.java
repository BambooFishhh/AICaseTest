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
public class EntityInfo {

    private String name;

    private List<Map<String, Object>> fields;

    private String file;

    private String description;

    private List<Map<String, Object>> fieldConstraints;

    private List<Map<String, Object>> relationships;

    private List<String> sources;

    public Map<String, Object> toContextMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("fields", fields == null ? List.of() : fields);
        map.put("file", file);
        map.put("description", description == null ? "" : description);
        map.put("fieldConstraints", fieldConstraints == null ? List.of() : fieldConstraints);
        map.put("relationships", relationships == null ? List.of() : relationships);
        map.put("sources", sources == null ? List.of() : sources);
        return map;
    }
}
