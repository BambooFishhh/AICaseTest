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

    /**
     * v13.18(证据权威判定): 生成期待裁决快照。
     *
     * <p>本轮对账存在 {@code authority=human} 的冲突时，该批用例整体标为 {@code pending}；
     * 为空表示生成时不存在待裁决冲突（冲突裁决后重生成会自然清除）。
     *
     * <p>注意与"执行结果"区分：执行断言的三值（passed/failed/skipped）存在执行记录上，
     * 本字段只承载证据链裁决状态（pending / prd_authoritative / code_authoritative / deprecated）。
     */
    @Column(name = "verdict", length = 24)
    private String verdict;

    /** v13.18: 关联的冲突 key 集合（逗号分隔；项目级快照，非逐条关联） */
    @Column(name = "conflict_ref", length = 512)
    private String conflictRef;

    /** v13.18: 关联冲突的维度集合（逗号分隔） */
    @Column(name = "dimension", length = 64)
    private String dimension;
}
