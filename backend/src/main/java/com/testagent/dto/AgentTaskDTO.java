package com.testagent.dto;

import com.testagent.entity.AgentTask;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskDTO {

    private String id;
    private String requestId;
    private String taskType;
    private String projectId;
    private String status;
    private String phase;
    private Integer attempts;
    private Integer maxAttempts;
    private String errorCode;
    private String errorMessage;
    private Boolean degraded;
    private String leaseOwner;
    private LocalDateTime leaseExpireAt;
    private LocalDateTime heartbeatAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String checkpointJson;

    public static AgentTaskDTO from(AgentTask t) {
        return AgentTaskDTO.builder()
                .id(t.getId())
                .requestId(t.getRequestId())
                .taskType(t.getTaskType())
                .projectId(t.getProjectId())
                .status(t.getStatus())
                .phase(t.getPhase())
                .attempts(t.getAttempts())
                .maxAttempts(t.getMaxAttempts())
                .errorCode(t.getErrorCode())
                .errorMessage(t.getErrorMessage())
                .degraded(t.getDegraded())
                .leaseOwner(t.getLeaseOwner())
                .leaseExpireAt(t.getLeaseExpireAt())
                .heartbeatAt(t.getHeartbeatAt())
                .startedAt(t.getStartedAt())
                .endedAt(t.getEndedAt())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .checkpointJson(t.getCheckpointJson())
                .build();
    }
}
