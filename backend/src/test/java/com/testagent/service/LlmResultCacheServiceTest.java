package com.testagent.service;

import com.testagent.entity.LlmResultCache;
import com.testagent.repository.LlmResultCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v7.5(A11/A15): LLM 结果缓存层——键确定性、模型名入键、kind 校验、
 * 并发冲突静默、DB 异常降级（绝不阻断分析/生成）。
 */
class LlmResultCacheServiceTest {

    private LlmResultCacheRepository repository;
    private LlmResultCacheService service;

    @BeforeEach
    void setUp() {
        repository = mock(LlmResultCacheRepository.class);
        service = new LlmResultCacheService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "model", "gpt-4o");
    }

    @Test
    void sameInputRoundTrips() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        service.put("prd_analysis", "sys", "user", "{\"requirements\":[]}");
        verify(repository).save(any(LlmResultCache.class));

        when(repository.findById(anyString()))
                .thenReturn(Optional.of(new LlmResultCache("k", "prd_analysis", "{\"requirements\":[]}")));
        assertEquals("{\"requirements\":[]}", service.get("prd_analysis", "sys", "user"));
    }

    @Test
    void differentInputMisses() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        assertNull(service.get("prd_analysis", "sys", "user-a"));
        assertNull(service.get("prd_analysis", "sys-a", "user"));
        assertNull(service.get("prd_analysis-a", "sys", "user"));
    }

    // 模型名入键：换模型自动全量失效
    @Test
    void modelNameParticipatesInKey() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(service, "model", "gpt-4.1");
        assertNull(service.get("prd_analysis", "sys", "user"));
    }

    // kind 不参与键计算但 get 校验一致性——同键不同 kind 不得串用
    @Test
    void getValidatesKind() {
        when(repository.findById(anyString()))
                .thenReturn(Optional.of(new LlmResultCache("k", "component_summary", "resp")));
        assertNull(service.get("prd_analysis", "sys", "user"));
        assertEquals("resp", service.get("component_summary", "sys", "user"));
    }

    @Test
    void putOverwritesExistingEntry() {
        when(repository.findById(anyString()))
                .thenReturn(Optional.of(new LlmResultCache("k", "prd_analysis", "old")));
        service.put("prd_analysis", "sys", "user", "new");
        ArgumentCaptor<LlmResultCache> captor = ArgumentCaptor.forClass(LlmResultCache.class);
        verify(repository).save(captor.capture());
        assertEquals("new", captor.getValue().getResultText());
        assertEquals("prd_analysis", captor.getValue().getCacheKind());
    }

    @Test
    void putIgnoresBlankResponse() {
        service.put("prd_analysis", "sys", "user", "");
        service.put("prd_analysis", "sys", "user", null);
        verify(repository, never()).save(any());
    }

    // 并发主键冲突（两写者内容相同）静默忽略
    @Test
    void putSilencesConcurrentConflict() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("dup")).when(repository).save(any());
        assertDoesNotThrow(() -> service.put("prd_analysis", "sys", "user", "resp"));
    }

    // DB 异常降级：get 返回 null（落回直调 LLM），put 不抛
    @Test
    void dbFailureDegradesToDirectLlm() {
        when(repository.findById(anyString())).thenThrow(new RuntimeException("db down"));
        assertNull(service.get("prd_analysis", "sys", "user"));

        doThrow(new RuntimeException("db down")).when(repository).save(any());
        assertDoesNotThrow(() -> service.put("prd_analysis", "sys", "user", "resp"));
    }
}
