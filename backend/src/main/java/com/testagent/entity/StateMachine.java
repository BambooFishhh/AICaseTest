package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "state_machines")
@Data
public class StateMachine {

    @Id
    private String id;

    @Column(name = "project_id")
    private String projectId;

    private String name;

    @Column(columnDefinition = "TEXT")
    private String description = "";

    @Column(columnDefinition = "TEXT")
    private String states = "[]";

    @Column(columnDefinition = "TEXT")
    private String transitions = "[]";

    @Column(name = "forbidden_transitions", columnDefinition = "TEXT")
    private String forbiddenTransitions = "[]";

    private double confidence = 0.0;

    @Column(columnDefinition = "TEXT")
    private String sources = "[]";

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
