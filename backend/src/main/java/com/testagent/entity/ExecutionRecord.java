package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * v2.0: 测试用例执行记录
 */
@Entity
@Table(name = "execution_record")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionRecord {

    @Id
    private String id;

    private String projectId;

    private String testCaseId;

    private String testCaseTitle;

    /** pending / running / passed / failed */
    private String status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 通过/失败/跳过的步骤数摘要 */
    private String summary;

    private String errorMessage;

    /** v2.1: 批次 ID（单条执行时为 null） */
    private String batchId;

    /** v2.1: 执行模式 programmatic / agent */
    private String mode;

    /** v2.4: 录屏帧文件路径列表（JSON 数组字符串） */
    @Column(length = 4096)
    @Builder.Default
    private String recordingFrames = "[]";
}
