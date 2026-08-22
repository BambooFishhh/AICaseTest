package com.testagent.service;

import com.testagent.entity.AgentTask;
import com.testagent.repository.AgentTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentCaptor;

class AgentTaskServiceTest {

    private AgentTaskRepository repository;
    private AgentTaskService service;

    @BeforeEach
    void setUp() {
        repository = mock(AgentTaskRepository.class);
        service = new AgentTaskService();
        ReflectionTestUtils.setField(service, "agentTaskRepository", repository);
        ReflectionTestUtils.setField(service, "taskLeaseSeconds", 600);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createTaskReusesActiveTaskForSameRequest() {
        AgentTask existing = new AgentTask();
        existing.setId("task-1");
        existing.setStatus(AgentTaskService.STATUS_RUNNING);
        when(repository.findFirstByRequestIdAndTaskTypeOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.of(existing));

        String id = service.createTask(AgentTaskService.TYPE_GENERATION, "p1", "p1", "{}");

        assertEquals("task-1", id);
        verify(repository, never()).save(any());
    }

    @Test
    void createTaskCreatesWhenNoActiveTask() {
        when(repository.findFirstByRequestIdAndTaskTypeOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());

        String id = service.createTask(AgentTaskService.TYPE_ANALYSIS, "p1", "p1", "{}");

        assertNotNull(id);
        verify(repository).save(any(AgentTask.class));
    }

    @Test
    void createTaskWithIdUsesProvidedTaskId() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String id = service.createTaskWithId("exec-1", AgentTaskService.TYPE_EXECUTION,
                "p1", "exec-1", "{}");

        assertEquals("exec-1", id);
        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(repository).save(captor.capture());
        assertEquals("exec-1", captor.getValue().getId());
        assertEquals(AgentTaskService.STATUS_QUEUED, captor.getValue().getStatus());
    }

    @Test
    void startSetsRunningLeaseAndAttemptCount() {
        AgentTask task = new AgentTask();
        task.setId("task-1");
        task.setStatus(AgentTaskService.STATUS_QUEUED);
        task.setAttempts(0);
        when(repository.findById("task-1")).thenReturn(Optional.of(task));

        service.start("task-1");

        assertEquals(AgentTaskService.STATUS_RUNNING, task.getStatus());
        assertEquals(1, task.getAttempts());
        assertNotNull(task.getLeaseOwner());
        assertNotNull(task.getLeaseExpireAt());
    }

    @Test
    void recoverStaleTasksMarksNeedsReview() {
        AgentTask stale = new AgentTask();
        stale.setId("task-1");
        stale.setStatus(AgentTaskService.STATUS_RUNNING);
        stale.setLeaseExpireAt(LocalDateTime.now().minusMinutes(1));
        when(repository.findByStatusAndLeaseExpireAtBefore(anyString(), any()))
                .thenReturn(List.of(stale));

        int count = service.recoverStaleTasks();

        assertEquals(1, count);
        assertEquals(AgentTaskService.STATUS_NEEDS_REVIEW, stale.getStatus());
        assertEquals("LEASE_EXPIRED", stale.getErrorCode());
        verify(repository).save(stale);
    }

    @Test
    void requeueMakesFailedTaskQueued() {
        AgentTask task = new AgentTask();
        task.setId("task-1");
        task.setStatus(AgentTaskService.STATUS_FAILED);
        task.setErrorMessage("boom");
        when(repository.findById("task-1")).thenReturn(Optional.of(task));

        service.requeue("task-1");

        assertEquals(AgentTaskService.STATUS_QUEUED, task.getStatus());
        assertNull(task.getErrorMessage());
    }

    @Test
    void expireTasksByTtlMarksNeedsReview() {
        AgentTask stale = new AgentTask();
        stale.setId("task-1");
        stale.setStatus(AgentTaskService.STATUS_RUNNING);
        stale.setStartedAt(LocalDateTime.now().minusMinutes(90));
        when(repository.findByStatusAndStartedAtBefore(anyString(), any()))
                .thenReturn(List.of(stale));

        int count = service.expireTasksByTtl();

        assertEquals(1, count);
        assertEquals(AgentTaskService.STATUS_NEEDS_REVIEW, stale.getStatus());
        assertEquals("TTL_EXCEEDED", stale.getErrorCode());
        verify(repository).save(stale);
    }

    @Test
    void findQueuedReadsQueuedTasks() {
        AgentTask queued = new AgentTask();
        queued.setId("task-q");
        queued.setStatus(AgentTaskService.STATUS_QUEUED);
        when(repository.findTop20ByStatusOrderByCreatedAtAsc(AgentTaskService.STATUS_QUEUED))
                .thenReturn(List.of(queued));

        List<AgentTask> result = service.findQueued();

        assertEquals(1, result.size());
        assertEquals("task-q", result.get(0).getId());
    }

    @Test
    void markDegradedSetsFlag() {
        AgentTask task = new AgentTask();
        task.setId("task-1");
        task.setStatus(AgentTaskService.STATUS_RUNNING);
        task.setDegraded(false);
        when(repository.findById("task-1")).thenReturn(Optional.of(task));

        service.markDegraded("task-1");

        assertTrue(task.getDegraded());
    }

    @Test
    void getAttemptReadsTaskAttempts() {
        AgentTask task = new AgentTask();
        task.setId("task-1");
        task.setAttempts(2);
        when(repository.findById("task-1")).thenReturn(Optional.of(task));

        assertEquals(2, service.getAttempt("task-1"));
    }

    @Test
    void claimQueuedUsesCasUpdate() {
        when(repository.claimQueued(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any())).thenReturn(1);

        assertTrue(service.claimQueued("task-1"));
    }
}
