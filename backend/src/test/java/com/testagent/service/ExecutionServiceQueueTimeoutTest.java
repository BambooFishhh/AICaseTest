package com.testagent.service;

import com.testagent.entity.ExecutionRecord;
import com.testagent.entity.TestCase;
import com.testagent.repository.ExecutionRecordRepository;
import com.testagent.runtime.RuntimeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v7.11(E13): 排队超时收尾单测。
 * 背景：排队中的任务被用户取消后，排队超时路径仍会把记录翻转为 failed、
 * 且 exec:cancel 标志不清理（内存版 RuntimeStore 永久残留，影响同 ID 复用）。
 */
class ExecutionServiceQueueTimeoutTest {

    private ExecutionService service;
    private ExecutionRecordRepository executionRecordRepository;
    private ProjectExecutionLimiter limiter;
    private RuntimeStore runtimeStore;
    private AgentTaskService agentTaskService;

    @BeforeEach
    void setUp() {
        service = new ExecutionService();
        executionRecordRepository = mock(ExecutionRecordRepository.class);
        limiter = mock(ProjectExecutionLimiter.class);
        runtimeStore = mock(RuntimeStore.class);
        agentTaskService = mock(AgentTaskService.class);
        TaskQueueService taskQueueService = mock(TaskQueueService.class);

        ReflectionTestUtils.setField(service, "executionRecordRepository", executionRecordRepository);
        ReflectionTestUtils.setField(service, "projectExecutionLimiter", limiter);
        ReflectionTestUtils.setField(service, "runtimeStore", runtimeStore);
        ReflectionTestUtils.setField(service, "agentTaskService", agentTaskService);
        ReflectionTestUtils.setField(service, "taskQueueService", taskQueueService);
        ReflectionTestUtils.setField(service, "projectAcquireTimeoutMinutes", 1);

        when(executionRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 排队超时：tryAcquire 直接失败
        // v7.12(E15): 签名追加 permitId（=executionId），语义不变
        when(limiter.tryAcquire(anyString(), anyLong(), anyString())).thenReturn(false);
    }

    private TestCase testCase() {
        TestCase tc = new TestCase();
        tc.setId("c1");
        tc.setProjectId("p1");
        return tc;
    }

    private Boolean acquire(String executionId, TestCase tc) {
        return ReflectionTestUtils.invokeMethod(service, "acquireProjectPermitOrTimeout",
                executionId, tc, false);
    }

    @Test
    void queueTimeoutKeepsCancelledStatus() {
        // E13 核心回归：排队期间被取消 → 终态 cancelled 保持不变，不得翻转 failed
        ExecutionRecord record = new ExecutionRecord();
        record.setId("exec-1");
        record.setStatus("cancelled");
        when(executionRecordRepository.findById("exec-1")).thenReturn(Optional.of(record));

        Boolean acquired = acquire("exec-1", testCase());

        assertFalse(acquired);
        assertEquals("cancelled", record.getStatus());
        assertEquals(null, record.getErrorMessage());
    }

    @Test
    void queueTimeoutMarksPendingAsFailed() {
        // 未取消的排队超时仍按 failed 收尾（v7.9(E7) 语义保持）
        ExecutionRecord record = new ExecutionRecord();
        record.setId("exec-2");
        record.setStatus("pending");
        when(executionRecordRepository.findById("exec-2")).thenReturn(Optional.of(record));

        Boolean acquired = acquire("exec-2", testCase());

        assertFalse(acquired);
        assertEquals("failed", record.getStatus());
        assertEquals("项目执行并发排队超时", record.getSummary());
    }

    @Test
    void queueTimeoutClearsResidualCancelFlag() {
        // E13：取消标志必须清理——内存版 RuntimeStore 的 flag 无 TTL，残留会影响后续同 ID 执行
        ExecutionRecord record = new ExecutionRecord();
        record.setId("exec-3");
        record.setStatus("pending");
        when(executionRecordRepository.findById("exec-3")).thenReturn(Optional.of(record));

        acquire("exec-3", testCase());

        verify(runtimeStore).clearFlag(eq("exec:cancel:exec-3"));
        verify(runtimeStore).removeHeartbeat(eq("exec-3"));
    }
}
