package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "test_cases")
@Data
public class TestCase {

    @Id
    private String id;

@Column(name = "project_id")
private String projectId;

// v7.15(2a): 项目内展示序号——id 仍为全局唯一 TC-xxx（防跨项目撞号静默覆盖），
// 展示层用项目内从 1 连续的编号，兼顾唯一性与可读性
@Column(name = "project_seq")
private Integer projectSeq;

    private String title;

    private String module;

    private String type = "positive";

    private String priority = "P1";

    @Column(columnDefinition = "TEXT")
    private String preconditions = "[]";

    @Column(columnDefinition = "TEXT")
    private String steps = "[]";

    @Column(name = "expected_results", columnDefinition = "TEXT")
    private String expectedResults = "[]";

    @Column(name = "state_machine_ref", columnDefinition = "TEXT")
    private String stateMachineRef = "{}";

    private String source = "ai_generation";

    private double confidence = 0.0;

    @Column(name = "structured_steps", columnDefinition = "TEXT")
    private String structuredSteps = "[]";

    @Column(name = "api_endpoints", columnDefinition = "TEXT")
    private String apiEndpoints = "[]";

    @Column(name = "test_data", columnDefinition = "TEXT")
    private String testData = "{}";

    @Column(name = "execution_hints", columnDefinition = "TEXT")
    private String executionHints = "{}";

    @Column(name = "execution_status")
    private String executionStatus = "not_executed";

    // v1.8: 评审状态 draft/reviewed/approved/rejected
    @Column(name = "review_status")
    private String reviewStatus = "draft";

    @Column(name = "quality_score")
    private Integer qualityScore = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
