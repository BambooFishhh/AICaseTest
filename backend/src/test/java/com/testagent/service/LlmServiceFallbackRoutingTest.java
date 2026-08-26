package com.testagent.service;

import com.testagent.common.BusinessException;
import com.testagent.config.LlmProviders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v8.8.1(10.2): 降级路由——主通道失败切 fallback 并打标；双通道失败抛 50300；
 * 未配置降级时保持单通道语义。
 */
class LlmServiceFallbackRoutingTest {

    private LlmService service;
    private ChatClient.CallResponseSpec primaryCall;
    private ChatClient.CallResponseSpec fallbackCall;

    @BeforeEach
    void setUp() {
        primaryCall = mock(ChatClient.CallResponseSpec.class);
        fallbackCall = mock(ChatClient.CallResponseSpec.class);
        service = new LlmService() {
            private int primaryCalls = 0;

            @Override
            protected ChatClient chatClientFor(String providerName) {
                if ("primary".equals(providerName)) {
                    primaryCalls++;
                    // 主通道：每次调用都抛可重试异常
                    throw new RuntimeException("primary upstream 503");
                }
                return stubClient(fallbackCall);
            }

            int primaryAttempts() {
                return primaryCalls;
            }
        };
        ReflectionTestUtils.setField(service, "telemetryService", mock(TelemetryService.class));
        ReflectionTestUtils.setField(service, "model", "m1");
        ReflectionTestUtils.setField(service, "maxRetries", 1);
    }

    private ChatClient stubClient(ChatClient.CallResponseSpec call) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        return client;
    }

    private void enableFallback() {
        LlmProviders providers = new LlmProviders();
        ReflectionTestUtils.setField(providers, "fallbackBaseUrl", "https://fb.example.com");
        ReflectionTestUtils.setField(providers, "fallbackApiKey", "k");
        ReflectionTestUtils.setField(providers, "fallbackModel", "gpt-x");
        ReflectionTestUtils.setField(providers, "fallbackMaxPromptChars", 200000);
        ReflectionTestUtils.setField(service, "providers", providers);
    }

    @Test
    void routesToFallbackAndMarksDegradedWhenConfigured() {
        enableFallback();
        when(fallbackCall.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{\"ok\":true}")))));

        Map<String, Object> result = service.chatJson("sys", "user", 0.2);

        assertTrue(result.containsKey("ok"));
        assertEquals("fallback", service.consumeDegradedProvider());
    }

    @Test
    void singleChannelSemanticsPreservedWithoutFallback() {
        // 未注入 providers：主通道失败原样抛 50002
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.chatJson("sys", "user", 0.2));

        assertEquals(50002, ex.getCode());
        assertNull(service.consumeDegradedProvider());
    }

    @Test
    void bothChannelsDownThrows50300() {
        enableFallback();
        when(fallbackCall.chatResponse()).thenThrow(new RuntimeException("fallback down too"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.chatJson("sys", "user", 0.2));

        assertEquals(50300, ex.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getHttpStatus());
    }
}
