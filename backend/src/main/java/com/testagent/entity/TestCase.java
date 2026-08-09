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

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
