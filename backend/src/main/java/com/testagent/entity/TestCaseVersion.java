package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

// v1.9: 用例版本快照
@Entity
@Table(name = "test_case_versions")
@Data
public class TestCaseVersion {

    @Id
    private String id;  // UUID

    @Column(name = "test_case_id")
    private String testCaseId;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "version_no")
    private Integer versionNo;

    @Column(columnDefinition = "TEXT")
    private String snapshot;  // 用例完整字段 JSON 快照

    private String action;  // edit / rollback

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
