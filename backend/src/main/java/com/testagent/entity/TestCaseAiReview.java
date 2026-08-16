package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

// v5.12: AI 评审历史，记录每次生成/重评的评审结果
@Entity
@Table(name = "test_case_ai_reviews")
@Data
public class TestCaseAiReview {

    @Id
    private String id;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "test_case_id")
    private String testCaseId;

    private String status;

    @Column(columnDefinition = "TEXT")
    private String issues = "[]";

    @Column(name = "suggested_changes", columnDefinition = "TEXT")
    private String suggestedChanges = "{}";

    @Column(name = "coverage_refs", columnDefinition = "TEXT")
    private String coverageRefs = "{}";

    private Double confidence;

    private String source = "generation";

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
