package com.testagent.service;

import com.testagent.entity.AgentTask;
import com.testagent.repository.AgentTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * v6.5: agent_task 生命周期管理。业务异步线程负责 create/start/checkpoint/收尾，
 * 恢复逻辑只处理 lease 过期的 RUNNING 任务并标记 NEEDS_REVIEW，不自动重跑。
 */
@Service
public class AgentTaskService {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskService.class);

    public static final String TYPE_ANALYSIS = "analysis";
    public static final String TYPE_GENERATION = "generation";
    public static final String TYPE_APPEND_GENERATION = "append_generation";

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_NEEDS_REVIEW = "NEEDS_REVIEW";
    public static final String STATUS_DLQ = "DLQ";

    @Autowired
    private AgentTaskRepository agentTaskRepository;

    @Value("${app.ha.task-lease-seconds:600}")
    private int taskLeaseSeconds = 600;

    public String createTask(String taskType, String projectId, String requestId, String inputJson) {
        AgentTask latest = agentTaskRepository
                .findFirstByRequestIdAndTaskTypeOrderByCreatedAtDesc(requestId, taskType)
                .orElse(null);
        if (latest != null && isActive(latest.getStatus())) {
            log.debug("Reuse active agent task {} for {}:{}", latest.getId(), taskType, requestId);
            return latest.getId();
        }

        AgentTask task = new AgentTask();
        task.setId(UUID.randomUUID().toString());
        task.setRequestId(requestId);
        task.setTaskType(taskType);
        task.setProjectId(projectId);
        task.setStatus(STATUS_QUEUED);
        task.setPhase("created");
        task.setAttempts(0);
        task.setMaxAttempts(3);
        task.setInputJson(inputJson);
        task.setDegraded(false);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return agentTaskRepository.save(task).getId();
    }

    public void start(String taskId) {
        update(taskId, task -> {
            task.setStatus(STATUS_RUNNING);
            task.setPhase("started");
            task.setAttempts((task.getAttempts() == null ? 0 : task.getAttempts()) + 1);
            task.setErrorCode(null);
            task.setErrorMessage(null);
            task.setLeaseOwner(UUID.randomUUID().toString());
            task.setLeaseExpireAt(LocalDateTime.now().plusSeconds(taskLeaseSeconds));
            task.setHeartbeatAt(LocalDateTime.now());
            task.setStartedAt(LocalDateTime.now());
            task.setEndedAt(null);
        });
    }

    public void checkpoint(String taskId, String phase, String checkpointJson) {
        update(taskId, task -> {
            task.setPhase(phase);
            task.setCheckpointJson(checkpointJson);
            task.setHeartbeatAt(LocalDateTime.now());
            task.setLeaseExpireAt(LocalDateTime.now().plusSeconds(taskLeaseSeconds));
        });
    }

    public void succeed(String taskId) {
        update(taskId, task -> {
            task.setStatus(STATUS_SUCCEEDED);
            task.setPhase("completed");
            task.setEndedAt(LocalDateTime.now());
            task.setLeaseOwner(null);
            task.setLeaseExpireAt(null);
        });
    }

    public void fail(String taskId, String errorCode, String errorMessage) {
        update(taskId, task -> finishFailure(task, STATUS_FAILED, errorCode, errorMessage));
    }

    public void cancel(String taskId) {
        update(taskId, task -> finishFailure(task, STATUS_CANCELLED, "USER_CANCELLED",
                "用户取消任务"));
    }

    public void markNeedsReview(String taskId, String errorCode, String errorMessage) {
        update(taskId, task -> finishFailure(task, STATUS_NEEDS_REVIEW, errorCode, errorMessage));
    }

    public void markDlq(String taskId, String errorCode, String errorMessage) {
        update(taskId, task -> finishFailure(task, STATUS_DLQ, errorCode, errorMessage));
    }

    public void requeue(String taskId) {
        update(taskId, task -> {
            task.setStatus(STATUS_QUEUED);
            task.setPhase("queued");
            task.setErrorCode(null);
            task.setErrorMessage(null);
            task.setEndedAt(null);
            task.setLeaseOwner(null);
            task.setLeaseExpireAt(null);
        });
    }

    public AgentTask findById(String taskId) {
        return agentTaskRepository.findById(taskId).orElse(null);
    }

    public Page<AgentTask> list(String taskType, String status, String projectId, int page, int size) {
        Specification<AgentTask> spec = Specification.where(null);
        if (taskType != null && !taskType.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("taskType"), taskType));
        }
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (projectId != null && !projectId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("projectId"), projectId));
        }
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return agentTaskRepository.findAll(spec, pageable);
    }

    public Map<String, Long> statusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : agentTaskRepository.countGroupByStatus()) {
            counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return counts;
    }

    /**
     * 启动恢复 + 定时恢复：lease 过期的 RUNNING 任务标记 NEEDS_REVIEW，由管理员确认后重试。
     */
    @Scheduled(cron = "${app.ha.recovery-cron:0 */5 * * * *}")
    public int recoverStaleTasks() {
        LocalDateTime cutoff = LocalDateTime.now();
        List<AgentTask> stale = agentTaskRepository
                .findByStatusAndLeaseExpireAtBefore(STATUS_RUNNING, cutoff);
        for (AgentTask task : stale) {
            task.setStatus(STATUS_NEEDS_REVIEW);
            task.setErrorCode("LEASE_EXPIRED");
            task.setErrorMessage("服务重启或租约过期导致任务中断，请在任务中心确认后重试");
            task.setEndedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            agentTaskRepository.save(task);
        }
        if (!stale.isEmpty()) {
            log.info("v6.5: recovered {} stale agent tasks -> NEEDS_REVIEW", stale.size());
        }
        return stale.size();
    }

    private void finishFailure(AgentTask task, String status, String errorCode, String errorMessage) {
        task.setStatus(status);
        task.setEndedAt(LocalDateTime.now());
        task.setErrorCode(errorCode);
        task.setErrorMessage(errorMessage);
        task.setLeaseOwner(null);
        task.setLeaseExpireAt(null);
    }

    private void update(String taskId, Consumer<AgentTask> mutator) {
        agentTaskRepository.findById(taskId).ifPresent(task -> {
            mutator.accept(task);
            task.setUpdatedAt(LocalDateTime.now());
            agentTaskRepository.save(task);
        });
    }

    private boolean isActive(String status) {
        return STATUS_CREATED.equals(status)
                || STATUS_QUEUED.equals(status)
                || STATUS_RUNNING.equals(status);
    }
}
