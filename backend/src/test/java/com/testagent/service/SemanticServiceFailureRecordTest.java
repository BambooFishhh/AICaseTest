package com.testagent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v7.10(R13): 失败经验入库治理单测——旧实现用 executionId 当主键，
 * 同一失败发生 10 次就 10 条记录占满 topK；语料只有 "action -> error"，
 * 需求形查询打动作形语料向量天然弱。
 * 新实现：稳定 ID = fail- + SHA-256(projectId + 归一化 action + 归一化 error) 前 16 位，
 * 写入前 deleteByIds 同 ID（同源失败覆盖不堆积）；语料补用例标题与页面 URL。
 */
class SemanticServiceFailureRecordTest {

    private SemanticService service;
    private MilvusService milvusService;
    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        service = new SemanticService();
        milvusService = mock(MilvusService.class);
        embeddingService = mock(EmbeddingService.class);
        when(milvusService.isEnabled()).thenReturn(true);
        when(embeddingService.isConfigured()).thenReturn(true);
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f, 0.3f));
        ReflectionTestUtils.setField(service, "milvusService", milvusService);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
    }

    private String insertedIdOf(int callIndex) {
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(milvusService, atLeastOnce()).insert(
                eq(MilvusService.COLLECTION_FAILURES), anyString(), idCaptor.capture(),
                anyString(), anyString(), anyString(), anyList());
        return idCaptor.getAllValues().get(callIndex);
    }

    @Test
    void sameFailureAcrossExecutionsSharesStableId() {
        // 同一失败发生在两次不同执行 → 同一稳定 ID（覆盖不堆积，而非两条重复记录）
        service.recordFailure("p1", "exec-1", "点击提交按钮", "TimeoutException: 30s",
                "下单用例", "http://localhost/order");
        service.recordFailure("p1", "exec-2", "点击提交按钮", "TimeoutException: 30s",
                "下单用例", "http://localhost/order");

        String firstId = insertedIdOf(0);
        String secondId = insertedIdOf(1);
        assertEquals(firstId, secondId, "同源失败（projectId+action+error 相同）应命中同一稳定 ID");
        assertTrue(firstId.startsWith("fail-"), "稳定 ID 前缀 fail-");
        assertEquals(16, firstId.substring(5).length(), "hash 取前 16 位十六进制");

        // 两次写入前都先 deleteByIds 同一 ID（覆盖语义）
        verify(milvusService, times(2)).deleteByIds(
                eq(MilvusService.COLLECTION_FAILURES), anyString(), eq(List.of(firstId)));
    }

    @Test
    void normalizedActionAndErrorHitSameId() {
        // 大小写差异归一化后是同一失败 → 同 ID
        service.recordFailure("p1", "exec-1", "点击提交按钮", "TimeoutException: 30s", null, null);
        service.recordFailure("p1", "exec-2", "点击提交按钮", "timeoutexception: 30s", null, null);

        assertEquals(insertedIdOf(0), insertedIdOf(1), "归一化后相同的 action/error 应命中同一 ID");
    }

    @Test
    void differentFailureOrProjectYieldsDifferentId() {
        service.recordFailure("p1", "exec-1", "点击提交按钮", "TimeoutException", null, null);
        service.recordFailure("p1", "exec-1", "点击提交按钮", "NoSuchElementException", null, null);
        service.recordFailure("p2", "exec-1", "点击提交按钮", "TimeoutException", null, null);

        String id1 = insertedIdOf(0);
        String id2 = insertedIdOf(1);
        String id3 = insertedIdOf(2);
        assertNotEquals(id1, id2, "error 不同 → ID 不同");
        assertNotEquals(id1, id3, "projectId 不同 → ID 不同");
    }

    @Test
    void corpusContainsTitleAndPageUrl() {
        service.recordFailure("p1", "exec-1", "点击提交按钮", "TimeoutException",
                "正常下单流程", "http://localhost/order");

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(milvusService).insert(eq(MilvusService.COLLECTION_FAILURES), anyString(), anyString(),
                anyString(), anyString(), textCaptor.capture(), anyList());

        String text = textCaptor.getValue();
        assertTrue(text.startsWith("[正常下单流程] "), "语料应以用例标题开头，实际: " + text);
        assertTrue(text.contains("[http://localhost/order] "), "语料应包含页面 URL");
        // 主体是归一化文本（小写）——与检索侧向量口径一致
        assertTrue(text.endsWith("点击提交按钮 -> timeoutexception"),
                "语料应保留归一化 action -> error 主体");
    }

    @Test
    void corpusSkipsBlankTitleAndUrl() {
        service.recordFailure("p1", "exec-1", "点击提交按钮", "TimeoutException", "", null);

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(milvusService).insert(eq(MilvusService.COLLECTION_FAILURES), anyString(), anyString(),
                anyString(), anyString(), textCaptor.capture(), anyList());

        assertEquals("点击提交按钮 -> timeoutexception", textCaptor.getValue(),
                "空标题/空 URL 应跳过，语料退化为归一化 action -> error");
    }
}
