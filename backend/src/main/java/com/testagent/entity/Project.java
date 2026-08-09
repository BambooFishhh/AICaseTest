package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Data
public class Project {

    @Id
    @Column(length = 8)
    private String id;

    private String name;

    @Column(name = "source_type")
    private String sourceType = "local_path";

    @Column(name = "source_path")
    private String sourcePath;

    @Column(name = "tech_stack", columnDefinition = "TEXT")
    private String techStack = "{}";

    private String status = "created";

    @Column(columnDefinition = "TEXT")
    private String settings = "{}";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
