package com.testagent.service;

import com.testagent.common.BusinessException;
import com.testagent.entity.TestCase;
import com.testagent.repository.ExecutionRecordRepository;
import com.testagent.repository.TestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v7.9(E7/E9/E10): 批量入口限流 + ID 加长 + 复制执行权限开关。
 */
class ExecutionServiceBatchLimitTest {

    private ExecutionService service;
    private ProjectAccessService accessService;
    private TestCaseRepository testCaseRepository;
    private ExecutionRecordRepository executionRecordRepository;
    private AgentTaskService agentTaskService;

    @BeforeEach
    void setUp() {
        service = new ExecutionService();
        accessService = mock(ProjectAccessService.class);
        testCaseRepository = mock(TestCaseRepository.class);
        executionRecordRepository = mock(ExecutionRecordRepository.class);
        agentTaskService = mock(AgentTaskService.class);
        TaskQueueService taskQueueService = mock(TaskQueueService.class);

        ReflectionTestUtils.setField(service, "projectAccessService", accessService);
        ReflectionTestUtils.setField(service, "testCaseRepository", testCaseRepository);
        ReflectionTestUtils.setField(service, "executionRecordRepository", executionRecordRepository);
        ReflectionTestUtils.setField(service, "agentTaskService", agentTaskService);
        ReflectionTestUtils.setField(service, "taskQueueService", taskQueueService);
        // 执行器替换为 no-op：入口测试不真正跑浏览器自动化
        ReflectionTestUtils.setField(service, "executionExecutor", (Executor) command -> { });
        doNothing().when(accessService).assertOperateAccess(any());
        doNothing().when(accessService).assertViewAccess(any());

        TestCase tc = new TestCase();
        tc.setId("c1");
        tc.setProjectId("p1");
        tc.setTitle("登录用例");
        when(testCaseRepository.findById(any())).thenReturn(Optional.of(tc));
    }

    private List<String> ids(int n) {
        return IntStream.range(0, n).mapToObj(i -> "case-" + i).collect(Collectors.toList());
    }

    // ==================== E7: 批量入口限流 ====================

    @Test
    void batchOverLimitRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.executeBatch("p1", ids(101), "http://target"));
        assertEquals(50014, ex.getCode());
        assertTrue(ex.getMessage().contains("100"));
        verify(executionRecordRepository, never()).save(any());
    }

    @Test
    void copyExecuteOverLimitRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.copyExecute("p1", ids(101), "http://target", "agent"));
        assertEquals(50014, ex.getCode());
    }

    @Test
    void emptyBatchRejected() {
        assertThrows(BusinessException.class, () -> service.executeBatch("p1", List.of(), "http://target"));
        assertThrows(BusinessException.class, () -> service.copyExecute("p1", null, "http://target", "agent"));
    }

    @Test
    void batchAtLimitAccepted() {
        String batchId = service.executeBatch("p1", ids(100), "http://target");
        assertTrue(batchId.startsWith("batch-"));
        verify(executionRecordRepository, org.mockito.Mockito.times(100)).save(any());
    }

    // ==================== E9: ID 加长 ====================

    @Test
    void newIdIs16HexChars() {
        for (int i = 0; i < 200; i++) {
            String id = ExecutionService.newId();
            assertEquals(16, id.length(), "ID 应为 16 位十六进制（64bit）: " + id);
            assertTrue(id.matches("[0-9a-f]{16}"), "应为十六进制: " + id);
        }
    }

    @Test
    void executionRecordIdAtLimitAcceptedIs16Chars() {
        service.executeBatch("p1", ids(1), "http://target");
        ArgumentCaptor<com.testagent.entity.ExecutionRecord> captor =
                ArgumentCaptor.forClass(com.testagent.entity.ExecutionRecord.class);
        verify(executionRecordRepository).save(captor.capture());
        assertEquals(16, captor.getValue().getId().length());
        assertEquals(22, captor.getValue().getBatchId().length()); // "batch-" 6 + 16
    }

    // ==================== E10: 复制执行权限开关 ====================
    // 注：execute() 内部固定调用 assertOperateAccess，因此开关效果以 assertViewAccess
    // 的调用次数区分——false: 入口走 VIEW（view=1）；true: 入口走 OPERATE（view=0）。

    @Test
    void copyExecuteDefaultUsesViewAccess() {
        ReflectionTestUtils.setField(service, "copyExecuteRequireOperate", false);
        service.copyExecute("p1", ids(1), "http://target", "agent");
        verify(accessService).assertViewAccess("p1");
    }

    @Test
    void copyExecuteRequireOperateUsesOperateAccess() {
        ReflectionTestUtils.setField(service, "copyExecuteRequireOperate", true);
        service.copyExecute("p1", ids(1), "http://target", "agent");
        verify(accessService, never()).assertViewAccess("p1");
    }
}
