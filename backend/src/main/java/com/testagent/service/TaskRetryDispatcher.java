package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.common.BusinessException;
import com.testagent.dto.GenerateRequest;
import com.testagent.entity.AgentTask;
import com.testagent.entity.Project;
import com.testagent.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * v6.5: 管理端重试分发。独立组件避免 AgentTaskService 与业务 Service 循环依赖；
 * 追加生成依赖 SSE 客户端，不自动重放，引导用户在前端重新触发。
 */
@Component
public class TaskRetryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskRetryDispatcher.class);

    @Autowired
    private AgentTaskService agentTaskService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private TestCaseService testCaseService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ObjectMapper objectMapper;

    public Map<String, Object> retry(String taskId) {
        AgentTask task = agentTaskService.findById(taskId);
        if (task == null) {
            throw BusinessException.notFound("任务不存在: " + taskId);
        }
        String status = task.getStatus();
        if (!canRetry(status)) {
            throw BusinessException.invalidState("仅 FAILED/DLQ/NEEDS_REVIEW 状态可重试，当前状态: " + status);
        }

        if (AgentTaskService.TYPE_APPEND_GENERATION.equals(task.getTaskType())) {
            agentTaskService.requeue(taskId);
            agentTaskService.markNeedsReview(taskId, "MANUAL_RETRY",
                    "追加生成需要 SSE 客户端，请在前端重新触发");
            return Map.of(
                    "taskId", taskId,
                    "status", AgentTaskService.STATUS_NEEDS_REVIEW,
                    "dispatched", false,
                    "message", "追加生成需在前端重新触发");
        }

        agentTaskService.requeue(taskId);
        dispatch(task);
        return Map.of(
                "taskId", taskId,
                "status", AgentTaskService.STATUS_QUEUED,
                "dispatched", true);
    }

    /**
     * v6.6: 调度器入口，只处理 QUEUED 任务；追加生成保持人工重试语义。
     */
    public Map<String, Object> dispatchQueued(String taskId) {
        AgentTask task = agentTaskService.findById(taskId);
        if (task == null || !AgentTaskService.STATUS_QUEUED.equals(task.getStatus())) {
            return Map.of("taskId", taskId, "dispatched", false, "message", "任务不在排队状态");
        }
        // v7.0(E2): 执行任务由 executionExecutor 专属路径驱动；QUEUED→worker start() 的窗口内
        // 被通用分发 CAS 抢占会误标 NEEDS_REVIEW(UNSUPPORTED_RETRY)，必须在 claim 前跳过
        if (AgentTaskService.TYPE_EXECUTION.equals(task.getTaskType())) {
            return Map.of("taskId", taskId, "dispatched", false, "message", "执行任务由专属执行器驱动，跳过通用分发");
        }
        if (!agentTaskService.claimQueued(taskId)) {
            return Map.of("taskId", taskId, "dispatched", false, "message", "任务已被其他 worker 抢占");
        }
        if (AgentTaskService.TYPE_APPEND_GENERATION.equals(task.getTaskType())) {
            agentTaskService.markNeedsReview(taskId, "MANUAL_RETRY",
                    "追加生成需要 SSE 客户端，请在前端重新触发");
            return Map.of("taskId", taskId, "dispatched", false,
                    "message", "追加生成需在前端重新触发");
        }
        dispatch(agentTaskService.findById(taskId));
        return Map.of("taskId", taskId, "dispatched", true);
    }

    private void dispatch(AgentTask task) {
        try {
            switch (task.getTaskType()) {
                case AgentTaskService.TYPE_ANALYSIS -> {
                    Project project = projectRepository.findById(task.getProjectId()).orElse(null);
                    if (project == null) {
                        throw BusinessException.notFound("项目不存在: " + task.getProjectId());
                    }
                    analysisService.runAnalysisResume(project.getId(), project.getSourcePath(), task.getId());
                }
                case AgentTaskService.TYPE_GENERATION -> {
                    GenerateRequest request = parseGenerateRequest(task.getInputJson());
                    testCaseService.runGenerate(task.getProjectId(), request);
                }
                default -> agentTaskService.markNeedsReview(task.getId(), "UNSUPPORTED_RETRY",
                        "该任务类型不支持自动重试，请人工处理: " + task.getTaskType());
            }
        } catch (BusinessException e) {
            agentTaskService.markNeedsReview(task.getId(), "RETRY_DISPATCH_FAILED", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Task retry dispatch failed for {}", taskIdOf(task), e);
            agentTaskService.markNeedsReview(task.getId(), "RETRY_DISPATCH_FAILED", e.getMessage());
            throw new BusinessException(50003, "任务重试分发失败: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String taskIdOf(AgentTask task) {
        return task == null ? "unknown" : task.getId();
    }

    private GenerateRequest parseGenerateRequest(String inputJson) throws Exception {
        if (inputJson == null || inputJson.isBlank()) {
            return new GenerateRequest();
        }
        return objectMapper.readValue(inputJson, GenerateRequest.class);
    }

    private boolean canRetry(String status) {
        return AgentTaskService.STATUS_FAILED.equals(status)
                || AgentTaskService.STATUS_DLQ.equals(status)
                || AgentTaskService.STATUS_NEEDS_REVIEW.equals(status);
    }
}
