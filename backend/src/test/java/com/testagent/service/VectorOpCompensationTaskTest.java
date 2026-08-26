package com.testagent.service;

import com.testagent.entity.PendingVectorOp;
import com.testagent.repository.PendingVectorOpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// v8.6.1(9.2): 补偿重放三场景——成功 DONE / 失败退避 PENDING / 超限 DEAD
class VectorOpCompensationTaskTest {

    private VectorOpCompensationTask task;
    private PendingVectorOpRepository repo;
    private MilvusService milvusService;

    @BeforeEach
    void setUp() {
        task = new VectorOpCompensationTask();
        repo = mock(PendingVectorOpRepository.class);
        milvusService = mock(MilvusService.class);
        ReflectionTestUtils.setField(task, "pendingVectorOpRepository", repo);
        ReflectionTestUtils.setField(task, "milvusService", milvusService);
        task.setCompensationMaxAttempts(5);
    }

    private PendingVectorOp pendingOp(int attempts) {
        PendingVectorOp op = new PendingVectorOp();
        op.setId("op-1");
        op.setCollection("cases");
        op.setExpr("project_id == \"p1\"");
        op.setAttempts(attempts);
        op.setStatus(PendingVectorOp.STATUS_PENDING);
        return op;
    }

    @Test
    void replaySuccessMarksDone() {
        PendingVectorOp op = pendingOp(0);

        boolean ok = task.replayOne(op);

        assertTrue(ok);
        assertEquals(PendingVectorOp.STATUS_DONE, op.getStatus());
        assertEquals(0, op.getAttempts());
        verify(milvusService).deleteByRawExpr("cases", "project_id == \"p1\"");
        verify(repo).save(op);
    }

    @Test
    void replayFailureSchedulesBackoffAndStaysPending() {
        PendingVectorOp op = pendingOp(1);
        doThrow(new RuntimeException("milvus down")).when(milvusService)
                .deleteByRawExpr(anyString(), anyString());

        boolean ok = task.replayOne(op);

        assertFalse(ok);
        assertEquals(PendingVectorOp.STATUS_PENDING, op.getStatus());
        assertEquals(2, op.getAttempts());
        assertTrue(op.getNextAttemptAt().isAfter(LocalDateTime.now()));
        verify(repo).save(op);
    }

    @Test
    void replayFailureBeyondMaxAttemptsMarksDead() {
        PendingVectorOp op = pendingOp(4);
        doThrow(new RuntimeException("still failing")).when(milvusService)
                .deleteByRawExpr(anyString(), anyString());

        boolean ok = task.replayOne(op);

        assertFalse(ok);
        assertEquals(PendingVectorOp.STATUS_DEAD, op.getStatus());
        assertEquals(5, op.getAttempts());
        // DEAD 后不再排期
        assertEquals(null, op.getNextAttemptAt() == null ? null : op.getNextAttemptAt(),
                "DEAD 不应设置新的退避时间");
        verify(repo).save(op);
    }

    @Test
    void scheduledReplaySwallowsUnexpectedErrors() {
        // 整轮异常不外抛，调度不中断
        when(repo.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(anyString(), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("db glitch"));

        task.replayPendingOps();

        verify(milvusService, never()).deleteByRawExpr(anyString(), anyString());
    }
}
