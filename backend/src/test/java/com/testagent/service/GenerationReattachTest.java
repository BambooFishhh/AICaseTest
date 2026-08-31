package com.testagent.service;

import com.testagent.entity.AgentTask;
import com.testagent.entity.AgentTaskEvent;
import com.testagent.repository.TestCaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v9.1(2.1): 生成任务重接——attachGenerate 分支路由：
 * RUNNING+本地广播器 → 加入订阅者续播（不查 DB timeline）；
 * 无任务/终态/他实例 → 一次性回放 progress+case 事件并按终态收尾。
 * SseEmitter 未初始化时 send 走 early buffering，不抛异常，事件内容以联调验证为准。
 */
@ExtendWith(MockitoExtension.class)
class GenerationReattachTest {

    @Mock
    private AgentTaskService agentTaskService;

    @Mock
    private TestCaseRepository testCaseRepository;

    @InjectMocks
    private TestCaseService testCaseService;

    private AgentTask task(String status) {
        AgentTask task = new AgentTask();
        task.setId("t1");
        task.setProjectId("p1");
        task.setTaskType(AgentTaskService.TYPE_GENERATION);
        task.setStatus(status);
        return task;
    }

    private AgentTaskEvent event(String phase, String payload) {
        AgentTaskEvent ev = new AgentTaskEvent();
        ev.setTaskId("t1");
        ev.setPhase(phase);
        ev.setErrorMessage(payload);
        return ev;
    }

    @Test
    void noTaskCompletesWithExistingCaseCount() {
        when(agentTaskService.latestGenerationTask("p1")).thenReturn(null);
        when(testCaseRepository.findByProjectId("p1")).thenReturn(List.of());

        testCaseService.attachGenerate("p1", new SseEmitter(0L));

        verify(agentTaskService, never()).timeline(anyString());
        verify(testCaseRepository).findByProjectId("p1");
    }

    @Test
    void succeededTaskReplaysProgressAndCaseEvents() {
        when(agentTaskService.latestGenerationTask("p1")).thenReturn(task("SUCCEEDED"));
        when(agentTaskService.timeline("t1")).thenReturn(List.of(
                event("progress", "{\"message\":\"正在解析需求\"}"),
                event("case", "{\"title\":\"用例A\"}"),
                event("generate", "{\"ignored\":true}")));
        when(testCaseRepository.findByProjectId("p1")).thenReturn(List.of());

        testCaseService.attachGenerate("p1", new SseEmitter(0L));

        verify(agentTaskService).timeline("t1");
        verify(testCaseRepository).findByProjectId("p1");
    }

    @Test
    void cancelledTaskSkipsCaseCountLookup() {
        when(agentTaskService.latestGenerationTask("p1")).thenReturn(task("CANCELLED"));
        when(agentTaskService.timeline("t1")).thenReturn(List.of(
                event("case", "{\"title\":\"用例A\"}")));

        testCaseService.attachGenerate("p1", new SseEmitter(0L));

        verify(agentTaskService).timeline("t1");
        // 取消未落库：不计 DB 用例总数
        verify(testCaseRepository, never()).findByProjectId(anyString());
    }

    @Test
    void failedTaskSurfacesTaskError() {
        AgentTask failed = task("FAILED");
        failed.setErrorMessage("LLM 超时");
        when(agentTaskService.latestGenerationTask("p1")).thenReturn(failed);
        when(agentTaskService.timeline("t1")).thenReturn(List.of());

        testCaseService.attachGenerate("p1", new SseEmitter(0L));

        verify(agentTaskService).timeline("t1");
        verify(testCaseRepository, never()).findByProjectId(anyString());
    }

    @Test
    void runningTaskWithLocalBroadcastJoinsLiveAndSkipsTimeline() {
        when(agentTaskService.latestGenerationTask("p1")).thenReturn(task("RUNNING"));
        // 预置本实例广播器并塞入两条历史事件（重接应原样回放后加入订阅者）
        TestCaseService.GenerationBroadcast bc = testCaseService.registerBroadcast("p1");
        bc.history.add(new Object[]{"progress", java.util.Map.of("message", "正在生成用例")});
        bc.history.add(new Object[]{"case", java.util.Map.of("testCase", java.util.Map.of("title", "草稿1"))});
        SseEmitter emitter = new SseEmitter(0L);

        testCaseService.attachGenerate("p1", emitter);

        // 无缝续播：不加锁查 DB，连接进入订阅者列表
        verify(agentTaskService, never()).timeline(anyString());
        verify(testCaseRepository, never()).findByProjectId(anyString());
        synchronized (bc) {
            assertTrue(bc.subscribers.contains(emitter));
            assertEquals(2, bc.history.size());
        }
    }

    @Test
    void runningTaskOnOtherInstanceFallsBackToDbReplay() {
        when(agentTaskService.latestGenerationTask("p1")).thenReturn(task("RUNNING"));
        when(agentTaskService.timeline("t1")).thenReturn(List.of(
                event("case", "{\"title\":\"用例A\"}")));
        // 不预置广播器：模拟任务运行在其他实例（或本实例重启过）

        testCaseService.attachGenerate("p1", new SseEmitter(0L));

        verify(agentTaskService).timeline("t1");
    }
}
