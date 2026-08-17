package com.testagent.agent;

import com.testagent.common.BusinessException;
import com.testagent.service.LlmService;
import com.testagent.service.PromptSkillLoader;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrdAgentTest {

    private PrdAgent buildAgent(LlmService llmService) {
        PrdAgent agent = new PrdAgent();
        ReflectionTestUtils.setField(agent, "llmService", llmService);
        PromptSkillLoader promptSkillLoader = mock(PromptSkillLoader.class);
        when(promptSkillLoader.load(anyString(), anyString())).thenReturn("system prompt");
        ReflectionTestUtils.setField(agent, "promptSkillLoader", promptSkillLoader);
        return agent;
    }

    @Test
    void llmFailureMessageIsPropagated() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyDouble()))
                .thenThrow(new BusinessException(50002,
                        "LLM调用失败（已重试3次）: MCP [llm] 执行失败: MCP Server 错误: 403 Free quota exhausted",
                        HttpStatus.INTERNAL_SERVER_ERROR));

        PrdAgent agent = buildAgent(llmService);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                agent.analyze(List.of(Map.of(
                                "content", "订单模块需求文档",
                                "title", "主 PRD",
                                "docType", "prd")),
                        List.of(), null));

        assertTrue(ex.getMessage().contains("403 Free quota exhausted"));
    }

    @Test
    void emptyLlmResultIsRejected() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyDouble())).thenReturn("{}");

        PrdAgent agent = buildAgent(llmService);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                agent.analyze(List.of(Map.of(
                                "content", "订单模块需求文档",
                                "title", "主 PRD",
                                "docType", "prd")),
                        List.of(), null));

        assertTrue(ex.getMessage().contains("未提取到有效需求"));
    }
}
