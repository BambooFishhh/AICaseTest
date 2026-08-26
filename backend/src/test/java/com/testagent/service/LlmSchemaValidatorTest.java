package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// v8.6.2(9.5/9.6): 校验器矩阵 + 四 schema"真实样本通过 + 缺字段失败"双向测试
class LlmSchemaValidatorTest {

    private LlmSchemaValidator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        validator = new LlmSchemaValidator();
        ReflectionTestUtils.setField(validator, "mode", "observe");
    }

    private String validTestCases() {
        return """
                [{"title":"登录成功跳转首页","module":"auth","priority":"P0","type":"positive",
                  "steps":["打开登录页","输入正确账密","点击登录"],
                  "expectedResults":["跳转首页"],"requirementIds":["req-1"]}]
                """;
    }

    @Test
    void validTestCasesPasses() {
        assertTrue(validator.validateJson(validTestCases(), "test-cases").isEmpty());
    }

    @Test
    void missingTitleReportsFieldPath() {
        String json = "[{\"steps\":[\"a\"],\"priority\":\"P0\",\"type\":\"positive\"}]";
        List<String> errors = validator.validateJson(json, "test-cases");
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("title"));
    }

    @Test
    void dirtyEnumValueRejected() {
        // v8.6.2: type 白名单外的脏值（归一化前的源头）被契约拦截
        String json = "[{\"title\":\"t\",\"steps\":[],\"priority\":\"P0\",\"type\":\"冒烟\"}]";
        List<String> errors = validator.validateJson(json, "test-cases");
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("type"));
    }

    @Test
    void invalidJsonTextReportedAsError() {
        List<String> errors = validator.validateJson("这不是 JSON", "test-cases");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("合法 JSON"));
    }

    @Test
    void unknownSchemaNameRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateJson("{}", "no-such-schema"));
    }

    private void assertBidirectional(String schemaName, String validSample, String brokenSample) throws Exception {
        assertTrue(validator.validate(objectMapper.readTree(validSample), schemaName).isEmpty(),
                () -> schemaName + " 合法样本应通过");
        assertFalse(validator.validate(objectMapper.readTree(brokenSample), schemaName).isEmpty(),
                () -> schemaName + " 缺字段样本应失败");
    }

    @Test
    void prdAnalysisSchemaBidirectional() throws Exception {
        assertBidirectional("prd-analysis",
                "{\"modules\":[{\"name\":\"订单\"}],"
                        + "\"requirements\":[{\"title\":\"下单\",\"acceptanceCriteria\":[\"库存扣减\"]}],"
                        + "\"businessRules\":[{\"rule\":\"限购\",\"ruleType\":\"hard\"}],"
                        + "\"stateFlows\":[{\"name\":\"订单流\",\"states\":[],\"transitions\":[]}],"
                        + "\"entities\":[\"Order\"]}",
                "{\"modules\":\"不是数组\"}");
    }

    @Test
    void stateMachineSchemaBidirectional() throws Exception {
        assertBidirectional("state-machine",
                "[{\"name\":\"订单状态机\",\"description\":\"d\","
                        + "\"states\":[{\"code\":\"CREATED\"}],"
                        + "\"transitions\":[{\"from\":\"CREATED\",\"to\":\"PAID\",\"trigger\":\"支付\"}]}]",
                "[{\"name\":\"缺转换目标\",\"states\":[],\"transitions\":[{\"from\":\"A\"}]}]");
    }

    @Test
    void reviewResultSchemaBidirectional() throws Exception {
        assertBidirectional("review-result",
                "[{\"index\":0,\"status\":\"fix\",\"issues\":[\"缺 refs\"],\"confidence\":0.8,"
                        + "\"coverageRefs\":{},\"suggestedChanges\":null}]",
                "[{\"index\":-1,\"status\":\"不知道\"}]");
    }

    @Test
    void observeModePassesViolationsThrough() {
        ReflectionTestUtils.setField(validator, "mode", "observe");
        assertTrue(validator.validateStructured("[{}]", "test-cases", "unit-test"));
    }

    @Test
    void enforceModeBlocksViolations() {
        ReflectionTestUtils.setField(validator, "mode", "enforce");
        assertFalse(validator.validateStructured("[{}]", "test-cases", "unit-test"));
        assertTrue(validator.validateStructured(validTestCases(), "test-cases", "unit-test"));
    }
}
