package com.testagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v8.6.2(9.5): LLM 出参 schema 契约校验器。
 * schema 从 classpath:llm-schemas/{name}.json 加载（draft-07），编译结果缓存；
 * 灰度开关 llm.schema.mode：observe=失败仅计数告警放行（默认），enforce=阻断由调用方重试/降级。
 */
@Component
public class LlmSchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(LlmSchemaValidator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, JsonSchema> cache = new ConcurrentHashMap<>();

    // 配置三件套：yml 键 + @Value 默认值 + 字段初始化
    @Value("${llm.schema.mode:observe}")
    private String mode = "observe";

    // v8.7.1(9.5.3): 指标门面——no-op 兜底
    private com.testagent.observability.MetricsFacade metrics = new com.testagent.observability.MetricsFacade();

    @Autowired(required = false)
    void setMetrics(com.testagent.observability.MetricsFacade metrics) {
        this.metrics = metrics;
    }

    // v8.7.1(9.5.3): 契约违规计数（agent 标签=schemaName）
    void recordViolation(String schemaName) {
        metrics.increment("llm_schema_violation_total", "agent", schemaName);
    }

    @jakarta.annotation.PostConstruct
    void registerMetrics() {
        // v8.7.1: 启动零值预注册
        metrics.registerCounter("llm_schema_violation_total", "agent", "test-cases");
        metrics.registerCounter("llm_schema_violation_total", "agent", "prd-analysis");
        metrics.registerCounter("llm_schema_violation_total", "agent", "state-machine");
        metrics.registerCounter("llm_schema_violation_total", "agent", "review-result");
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isEnforce() {
        return "enforce".equalsIgnoreCase(mode);
    }

    public JsonSchema schema(String name) {
        return cache.computeIfAbsent(name, this::load);
    }

    private JsonSchema load(String name) {
        try (InputStream is = getClass().getResourceAsStream("/llm-schemas/" + name + ".schema.json")) {
            if (is == null) {
                throw new IllegalArgumentException("未知 LLM 出参契约 schema: " + name);
            }
            JsonNode schemaNode = objectMapper.readTree(is);
            SchemaValidatorsConfig config = new SchemaValidatorsConfig();
            // 严格类型——契约目标是拦类型漂移，宽松匹配会放过 string/number 互串
            config.setTypeLoose(false);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                    .getSchema(schemaNode, config);
        } catch (IOException e) {
            throw new IllegalArgumentException("加载 LLM 出参契约失败: " + name, e);
        }
    }

    /**
     * 校验 JSON 文本；非法 JSON 本身也作为错误返回（不抛异常，调用方统一处理错误列表）。
     */
    public List<String> validateJson(String json, String schemaName) {
        JsonNode node;
        try {
            node = objectMapper.readTree(json == null ? "" : json);
        } catch (Exception e) {
            return List.of("$: 响应不是合法 JSON: " + e.getMessage());
        }
        return validate(node, schemaName);
    }

    public List<String> validate(JsonNode node, String schemaName) {
        Set<ValidationMessage> messages = schema(schemaName).validate(node);
        return messages.stream()
                .map(m -> m.getInstanceLocation() + ": " + m.getMessage())
                .toList();
    }

    /**
     * 统一入口：true=通过或 observe 放行；false=enforce 且未通过（调用方走既有重试/降级路径）。
     * 校验器未注入场景由调用方 null 守卫覆盖。
     */
    public boolean validateStructured(String json, String schemaName, String caller) {
        List<String> errors = validateJson(json, schemaName);
        if (errors.isEmpty()) {
            return true;
        }
        recordViolation(schemaName);
        log.warn("LLM 出参契约校验未通过 (schema={}, caller={}, mode={}): {}",
                schemaName, caller, mode, errors);
        return !isEnforce();
    }
}
