package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "mindmaps")
@Data
public class MindMap {

    @Id
    private String id;

    @Column(name = "project_id")
    private String projectId;

    private String title;

    @Column(name = "file_path")
    private String filePath;

    @Column(columnDefinition = "TEXT")
    private String statistics = "{}";

    private String status = "pending";

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
