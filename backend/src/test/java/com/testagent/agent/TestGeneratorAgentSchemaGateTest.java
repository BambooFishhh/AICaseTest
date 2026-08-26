package com.testagent.agent;

import com.testagent.common.BusinessException;
import com.testagent.service.LlmSchemaValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v8.6.2(9.8): TestGeneratorAgent.parseTestCases 结构契约门禁——
 * observe 放行 / enforce 拦截（最高风险接入点直测；其余三点为同一行式调用）。
 */
class TestGeneratorAgentSchemaGateTest {

    private TestGeneratorAgent agent;
    private LlmSchemaValidator validator;

    @BeforeEach
    void setUp() {
        agent = new TestGeneratorAgent();
        validator = new LlmSchemaValidator();
    }

    private List<?> invokeParse(String json) {
        return (List<?>) ReflectionTestUtils.invokeMethod(
                agent, "parseTestCases", json, (Object) null);
    }

    @Test
    void nullValidatorKeepsLegacyBehavior() {
        // 未注入校验器：脏结构也照走旧逐条容错路径，不抛契约异常
        List<?> result = invokeParse("[{\"title\":\"t\"}]");
        assertEquals(1, result.size());
    }

    @Test
    void observeModePassesMalformedBatchThrough() {
        ReflectionTestUtils.setField(agent, "llmSchemaValidator", validator);
        validator.setMode("observe");

        // 缺 steps/priority/type 的条目：observe 放行进旧容错链路
        List<?> result = invokeParse("[{\"title\":\"t\"}]");
        assertTrue(result.isEmpty() || result.size() == 1);
    }

    @Test
    void enforceModeBlocksContractViolationBeforePerItemTolerance() {
        ReflectionTestUtils.setField(agent, "llmSchemaValidator", validator);
        validator.setMode("enforce");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeParse("[{\"title\":\"缺其他必填字段\"}]"));

        assertEquals(50002, ex.getCode());
        assertTrue(ex.getMessage().contains("test-cases"));
    }

    @Test
    void enforceModePassesWellFormedArray() {
        ReflectionTestUtils.setField(agent, "llmSchemaValidator", validator);
        validator.setMode("enforce");

        String json = """
                [{"title":"查询订单列表","module":"order","priority":"P1","type":"positive",
                  "steps":["进入列表页"],"expectedResults":["显示 10 条"],"requirementIds":["req-2"]}]
                """;
        List<?> result = invokeParse(json);

        assertEquals(1, result.size());
    }
}
