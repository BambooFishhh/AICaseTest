package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * v4.3: 项目组成员。role：VIEWER（只读）/ OPERATOR（增删改查+执行）。
 */
@Entity
@Table(name = "group_members")
@Data
public class GroupMember {

    @Id
    private String id;

    @Column(name = "group_id", nullable = false, length = 64)
    private String groupId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** VIEWER / OPERATOR */
    @Column(nullable = false, length = 16)
    private String role = "VIEWER";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
