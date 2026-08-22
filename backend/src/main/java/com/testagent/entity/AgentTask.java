package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * v6.5: 高可用任务状态机。分析/生成/追加生成都会在进入异步线程后写入任务记录，
 * 通过 lease/heartbeat 支持重启恢复，失败任务可人工确认后重试。
 */
@Entity
@Table(name = "agent_task")
@Data
public class AgentTask {

    @Id
    private String id;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "task_type")
    private String taskType;

    @Column(name = "project_id")
    private String projectId;

    private String status;

    private String phase;

    private Integer attempts = 0;

    @Column(name = "max_attempts")
    private Integer maxAttempts = 3;

    @Column(name = "input_json", columnDefinition = "LONGTEXT")
    private String inputJson;

    @Column(name = "checkpoint_json", columnDefinition = "LONGTEXT")
    private String checkpointJson;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    private Boolean degraded = false;

    @Column(name = "lease_owner")
    private String leaseOwner;

    @Column(name = "lease_expire_at")
    private LocalDateTime leaseExpireAt;

    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
