package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v8.6.2(9.7): chatJson 契约灰度矩阵——observe 放行不重试 / enforce 重试一次成功 /
 * enforce 二次仍失败抛 50002 / 括号配平提取。复用 LlmServiceTest 的 mock 链路模式。
 */
class LlmServiceChatJsonSchemaTest {

    private LlmService llmService;
    private ChatClient.CallResponseSpec callSpec;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);

        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);

        llmService = new LlmService();
        ReflectionTestUtils.setField(llmService, "chatClientBuilder", builder);
        ReflectionTestUtils.setField(llmService, "chatModel", mock(org.springframework.ai.chat.model.ChatModel.class));
        ReflectionTestUtils.setField(llmService, "embeddingModel", mock(EmbeddingModel.class));
        ReflectionTestUtils.setField(llmService, "telemetryService", mock(TelemetryService.class));
        ReflectionTestUtils.setField(llmService, "model", "qwen3.7-max");
    }

    private void respond(String... texts) {
        ChatResponse[] responses = new ChatResponse[texts.length];
        for (int i = 0; i < texts.length; i++) {
            responses[i] = new ChatResponse(
                    List.of(new Generation(new AssistantMessage(texts[i]))));
        }
        // 顺序返回：第一次调用返回第一个响应，重试返回后续响应
        ChatResponse[] rest = java.util.Arrays.copyOfRange(responses, 1, responses.length);
        if (rest.length == 0) {
            when(callSpec.chatResponse()).thenReturn(responses[0]);
        } else {
            when(callSpec.chatResponse()).thenReturn(responses[0], rest);
        }
    }

    private static final String BAD = "{\"requirements\": \"不是数组\"}";
    private static final String GOOD =
            "{\"modules\":[{\"name\":\"订单\"}],\"requirements\":[{\"title\":\"下单\"}]}";

    @Test
    void observeModeReturnsWithoutRetry() {
        respond(BAD);
        LlmSchemaValidator validator = new LlmSchemaValidator();
        validator.setMode("observe");
        llmService.setSchemaValidator(validator);

        Map<String, Object> result = llmService.chatJson("sys", "user", 0.2, "prd-analysis");

        assertEquals("不是数组", result.get("requirements"));
        verify(callSpec, times(1)).chatResponse();
    }

    @Test
    void enforceModeRetriesOnceWithHintThenSucceeds() {
        respond(BAD, GOOD);
        LlmSchemaValidator validator = new LlmSchemaValidator();
        validator.setMode("enforce");
        llmService.setSchemaValidator(validator);

        Map<String, Object> result = llmService.chatJson("sys", "user", 0.2, "prd-analysis");

        assertTrue(result.containsKey("modules"));
        verify(callSpec, times(2)).chatResponse();
    }

    @Test
    void enforceModeThrowsDegradationAfterFailedRetry() {
        respond(BAD, BAD);
        LlmSchemaValidator validator = new LlmSchemaValidator();
        validator.setMode("enforce");
        llmService.setSchemaValidator(validator);

        com.testagent.common.BusinessException ex = assertThrows(
                com.testagent.common.BusinessException.class,
                () -> llmService.chatJson("sys", "user", 0.2, "prd-analysis"));

        assertEquals(50002, ex.getCode());
        assertTrue(ex.getMessage().contains("prd-analysis"));
        verify(callSpec, times(2)).chatResponse();
    }

    @Test
    void legacyThreeArgChatJsonSkipsValidationEntirely() {
        respond(BAD);
        LlmSchemaValidator validator = new LlmSchemaValidator();
        validator.setMode("enforce");
        // schemaValidator 未注入（保持 null）→ 三参路径行为与历史完全一致

        Map<String, Object> result = llmService.chatJson("sys", "user", 0.2);

        assertTrue(result.containsKey("requirements"));
    }

    @Test
    void extractJsonObjectBalancesBracesInsideProse() throws Exception {
        var method = LlmService.class.getDeclaredMethod("extractJsonObject", String.class);
        method.setAccessible(true);
        ObjectMapper om = new ObjectMapper();

        // 说明文字含大括号 + 真实 JSON 在后：旧首尾截取会取错段，配平扫描应命中真实 JSON
        String noisy = "注意 {格式要求} 如下：{\"a\":{\"b\":1}} 以上。";
        String extracted = (String) method.invoke(llmService, noisy);
        Map<?, ?> parsed = om.readValue(extracted, Map.class);
        assertTrue(parsed.containsKey("a"));

        // 转义引号内的花括号不参与配平
        String escaped = "{\"text\":\"包含 } 与 { 的字符串\",\"ok\":true}";
        String extracted2 = (String) method.invoke(llmService, escaped);
        assertEquals(true, ((Map<?, ?>) om.readValue(extracted2, Map.class)).get("ok"));

        // 无配平段回落首尾截取兜底
        String unbalanced = "{\"broken\": 1";
        String extracted3 = (String) method.invoke(llmService, unbalanced);
        assertEquals("{\"broken\": 1", extracted3);
    }
}
