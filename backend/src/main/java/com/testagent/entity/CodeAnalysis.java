package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "code_analysis")
@Data
@NoArgsConstructor
public class CodeAnalysis {

    @Id
    private String id;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "frontend_result", columnDefinition = "TEXT")
    private String frontendResult = "{}";

    @Column(name = "backend_result", columnDefinition = "TEXT")
    private String backendResult = "{}";

    private String status = "pending";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public CodeAnalysis(String id, String projectId) {
        this.id = id;
        this.projectId = projectId;
        this.createdAt = LocalDateTime.now();
    }
}
