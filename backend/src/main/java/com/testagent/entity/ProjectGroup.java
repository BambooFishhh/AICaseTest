package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * v4.3: 项目组（部门）——同组成员可访问组内全部项目。
 */
@Entity
@Table(name = "project_groups")
@Data
public class ProjectGroup {

    @Id
    private String id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 255)
    private String description;

    /** 组创建者 */
    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

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
