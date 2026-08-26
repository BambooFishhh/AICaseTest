package com.testagent.observability;

import org.slf4j.MDC;

/**
 * v8.7.1(9.5.4): MDC 注入工具——生成/检索/索引入口按 projectId/taskId 打标，
 * try/finally 配对清理。logstash-encoder 的 JSON 日志自动携带 MDC 字段。
 */
public final class ObservabilityMdc {

    public static final String PROJECT_ID = "projectId";
    public static final String TASK_ID = "taskId";

    private ObservabilityMdc() {
    }

    public static void putProjectId(String projectId) {
        if (projectId != null && !projectId.isBlank()) {
            MDC.put(PROJECT_ID, projectId);
        }
    }

    public static void putTaskId(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            MDC.put(TASK_ID, taskId);
        }
    }

    public static void clear() {
        MDC.remove(PROJECT_ID);
        MDC.remove(TASK_ID);
    }
}
