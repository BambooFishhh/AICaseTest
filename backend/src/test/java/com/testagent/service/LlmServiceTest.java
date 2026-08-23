package com.testagent.service;

import com.testagent.common.BusinessException;
import com.testagent.common.GenerationCancelledException;
import com.testagent.dto.LlmCallResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmServiceTest {

    private ChatClient.Builder builder;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec callSpec;
    private ChatClient.StreamResponseSpec streamSpec;
    private TelemetryService telemetryService;
    private ChatModel chatModel;
    private EmbeddingModel embeddingModel;
    private LlmService llmService;

    private void setUpSuccessChain(ChatResponse response) {
        builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        telemetryService = mock(TelemetryService.class);
        chatModel = mock(ChatModel.class);
        embeddingModel = mock(EmbeddingModel.class);

        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(spec.stream()).thenReturn(streamSpec);
        when(callSpec.chatResponse()).thenReturn(response);
        when(streamSpec.chatResponse()).thenReturn(Flux.just(response));

        llmService = new LlmService();
        ReflectionTestUtils.setField(llmService, "chatClientBuilder", builder);
        ReflectionTestUtils.setField(llmService, "chatModel", chatModel);
        ReflectionTestUtils.setField(llmService, "embeddingModel", embeddingModel);
        ReflectionTestUtils.setField(llmService, "telemetryService", telemetryService);
        ReflectionTestUtils.setField(llmService, "model", "qwen3.7-max");
    }

    private ChatResponse response(String text, int prompt, int completion, int total) {
        Usage usage = new Usage() {
            @Override
            public Integer getPromptTokens() {
                return prompt;
            }

            @Override
            public Integer getCompletionTokens() {
                return completion;
            }

            @Override
            public Integer getTotalTokens() {
                return total;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().usage(usage).model("qwen3.7-max").build());
    }

    @Test
    void chatReturnsTextAndRecordsTelemetry() {
        ChatResponse resp = response("hello", 10, 5, 15);
        setUpSuccessChain(resp);

        LlmCallResult result = llmService.chatWithUsage("sys", "user", 0.4);

        assertEquals("hello", result.getText());
        assertEquals(10, result.getPromptTokens());
        assertEquals(5, result.getCompletionTokens());
        assertEquals(15, result.getTotalTokens());
        verify(telemetryService).recordLlmCall(result);
    }

    @Test
    void chatStreamingDeliversChunksAndReturnsFullText() {
        ChatResponse resp = response("streamed", 8, 4, 12);
        setUpSuccessChain(resp);

        AtomicBoolean chunkReceived = new AtomicBoolean(false);
        AtomicReference<String> chunk = new AtomicReference<>();
        String text = llmService.chatStreaming("sys", "user", 0.4, c -> {
            chunk.set(c);
            chunkReceived.set(true);
        });

        assertEquals("streamed", text);
        assertTrue(chunkReceived.get());
        assertEquals("streamed", chunk.get());
        verify(telemetryService).recordLlmCall(any(LlmCallResult.class));
    }

    @Test
    void chatJsonParsesFencedJson() {
        ChatResponse resp = response("```json\n{\"title\":\"TC-1\",\"type\":\"positive\"}\n```", 3, 3, 6);
        setUpSuccessChain(resp);

        Map<String, Object> parsed = llmService.chatJson("sys", "user", 0.2);

        assertEquals("TC-1", parsed.get("title"));
        assertEquals("positive", parsed.get("type"));
    }

    @Test
    void chatFailsAfterRetries() {
        builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        telemetryService = mock(TelemetryService.class);
        chatModel = mock(ChatModel.class);
        embeddingModel = mock(EmbeddingModel.class);

        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenThrow(new RuntimeException("upstream exploded"));

        llmService = new LlmService();
        ReflectionTestUtils.setField(llmService, "chatClientBuilder", builder);
        ReflectionTestUtils.setField(llmService, "chatModel", chatModel);
        ReflectionTestUtils.setField(llmService, "embeddingModel", embeddingModel);
        ReflectionTestUtils.setField(llmService, "telemetryService", telemetryService);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> llmService.chat("sys", "user", 0.4));

        assertTrue(ex.getMessage().contains("upstream exploded"));
        verify(telemetryService, never()).recordLlmCall(any());
    }

    @Test
    void isConfiguredReflectsChatModel() {
        setUpSuccessChain(response("x", 1, 1, 2));
        assertTrue(llmService.isConfigured());

        ReflectionTestUtils.setField(llmService, "chatModel", null, ChatModel.class);
        assertFalse(llmService.isConfigured());
    }

    @Test
    void testConnectionReportsSuccess() {
        setUpSuccessChain(response("ok", 1, 1, 2));

        Map<String, Object> result = llmService.testConnection();

        assertEquals("success", result.get("status"));
        assertTrue((Boolean) result.get("springAiChatModel"));
    }

    // ==================== v7.11(L14): 流式错误处理 ====================
    // 背景：error 信号不释放 latch → 外层 await 轮询死循环（线程挂死）；
    // 且 catch 块先查取消标志，真实网络错误被谎报为"用户取消生成"。

    @Test
    void streamingErrorReleasesLatchAndReportsRealCause() {
        setUpSuccessChain(response("x", 1, 1, 2));
        when(streamSpec.chatResponse())
                .thenReturn(Flux.error(new RuntimeException("network broken")));
        ReflectionTestUtils.setField(llmService, "maxRetries", 1);

        long start = System.currentTimeMillis();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> llmService.chatStreaming("sys", "user", 0.4, c -> { }));
        long elapsed = System.currentTimeMillis() - start;

        // L14 回归点 1：如实上报真实错误，不得谎报为用户取消
        assertTrue(ex.getMessage().contains("network broken"), ex.getMessage());
        assertFalse(ex.getMessage().contains("取消"), ex.getMessage());
        // L14 回归点 2：latch 被 error 信号释放，没有陷入 await 死循环
        assertTrue(elapsed < 5000, "应在 5 秒内失败，实际耗时 " + elapsed + "ms");
        verify(telemetryService, never()).recordLlmCall(any());
    }

    @Test
    void streamingCancellationStillPreserved() {
        setUpSuccessChain(response("streaming", 1, 1, 2));

        // doOnNext 内取消异常经 error 信号回流时，GenerationCancelledException 语义原样透传
        assertThrows(GenerationCancelledException.class,
                () -> llmService.chatStreaming("sys", "user", 0.4, c -> { }, () -> true));
    }
}
