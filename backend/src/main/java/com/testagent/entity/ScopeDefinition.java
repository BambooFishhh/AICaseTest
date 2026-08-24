package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

// v8.1: 一期迭代范围定义——基线 diff 识别 + LLM 补充 + 人工确认
@Entity
@Table(name = "scope_definition")
@Data
public class ScopeDefinition {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_CONFIRMED = "confirmed";

    @Id
    @Column(length = 8)
    private String id;

    @Column(name = "project_id", length = 8)
    private String projectId;

    private String name;

    // diff 起点：分支 / tag / commit
    @Column(name = "baseline_ref", length = 256)
    private String baselineRef;

    // 终点，默认 HEAD（预留）
    @Column(name = "head_ref", length = 256)
    private String headRef;

    private String status = STATUS_DRAFT;

    // 本期变更文件清单 JSON（追溯用）
    @Column(columnDefinition = "TEXT")
    private String changedFiles = "[]";

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
