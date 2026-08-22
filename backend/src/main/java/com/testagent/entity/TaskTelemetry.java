package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

// v5.14: 分析/生成/AI 评审任务耗时与 token 埋点
@Entity
@Table(name = "task_telemetry")
@Data
public class TaskTelemetry {

    @Id
    private String id;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "task_type")
    private String taskType;

    private Integer attempt;

    private String phase;

    private String status;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "first_token_ms")
    private Long firstTokenMs;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
