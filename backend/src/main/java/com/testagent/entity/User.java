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
 * v4.0: 系统用户。
 */
@Entity
@Table(name = "users")
@Data
public class User {

    @Id
    private String id;

    @Column(unique = true, nullable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", length = 64)
    private String displayName;

    /** USER / ADMIN */
    private String role = "USER";

    /**
     * v6.6: 首次登录/初始密码是否强制修改。默认管理员初始化为 true，
     * 修改密码成功后清除。grants 前端在改密前阻断主功能。
     */
    @Column(name = "must_change_password")
    private Boolean mustChangePassword = false;

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
