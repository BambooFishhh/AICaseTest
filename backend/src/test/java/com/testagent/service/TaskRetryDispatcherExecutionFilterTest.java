package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.entity.AgentTask;
import com.testagent.repository.ProjectRepository;
import com.testagent.service.TaskRetryDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v7.0(E2): 高可用调度器误伤执行任务的回归测试。
 * 业务背景：执行任务创建即 QUEUED，worker start() 之前存在窗口；v6.6 通用兜底分发
 * 无差别扫描 QUEUED 会 CAS 抢占执行任务并误标 NEEDS_REVIEW(UNSUPPORTED_RETRY)。
 * 修复后 dispatchQueued 必须在 claim 前跳过执行类型。
 */
class TaskRetryDispatcherExecutionFilterTest {

    private AgentTaskService agentTaskService;
    private TaskRetryDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        agentTaskService = mock(AgentTaskService.class);
        dispatcher = new TaskRetryDispatcher();
        ReflectionTestUtils.setField(dispatcher, "agentTaskService", agentTaskService);
        ReflectionTestUtils.setField(dispatcher, "analysisService", mock(AnalysisService.class));
        ReflectionTestUtils.setField(dispatcher, "testCaseService", mock(TestCaseService.class));
        ReflectionTestUtils.setField(dispatcher, "projectRepository", mock(ProjectRepository.class));
        ReflectionTestUtils.setField(dispatcher, "objectMapper", new ObjectMapper());
    }

    private AgentTask task(String type, String status) {
        AgentTask t = new AgentTask();
        t.setId("task-1");
        t.setTaskType(type);
        t.setStatus(status);
        t.setProjectId("p1");
        t.setInputJson("{}");
        return t;
    }

    @Test
    void executionTaskIsSkippedBeforeClaim() {
        // 执行任务在 QUEUED 窗口内被兜底分发扫到：不 claim、不误标 NEEDS_REVIEW
        when(agentTaskService.findById("task-1"))
                .thenReturn(task(AgentTaskService.TYPE_EXECUTION, AgentTaskService.STATUS_QUEUED));

        Map<String, Object> result = dispatcher.dispatchQueued("task-1");

        assertFalse((Boolean) result.get("dispatched"));
        verify(agentTaskService, never()).claimQueued(anyString());
        verify(agentTaskService, never()).markNeedsReview(anyString(), anyString(), anyString());
    }

    @Test
    void nonExecutionTaskStillDispatched() {
        // 普通生成任务不受过滤影响：照常 claim 并分发
        AgentTask generation = task(AgentTaskService.TYPE_GENERATION, AgentTaskService.STATUS_QUEUED);
        when(agentTaskService.findById("task-1")).thenReturn(generation);
        when(agentTaskService.claimQueued("task-1")).thenReturn(true);

        Map<String, Object> result = dispatcher.dispatchQueued("task-1");

        assertTrue((Boolean) result.get("dispatched"));
        verify(agentTaskService).claimQueued("task-1");
    }

    @Test
    void nonQueuedTaskIsIgnored() {
        when(agentTaskService.findById("task-1"))
                .thenReturn(task(AgentTaskService.TYPE_EXECUTION, AgentTaskService.STATUS_RUNNING));

        Map<String, Object> result = dispatcher.dispatchQueued("task-1");

        assertFalse((Boolean) result.get("dispatched"));
        verify(agentTaskService, never()).claimQueued(anyString());
        verify(agentTaskService, never()).markNeedsReview(any(), any(), any());
    }
}
