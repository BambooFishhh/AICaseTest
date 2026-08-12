package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * v3.15: 测试集/回归集——保存一组用例 ID，支持一键批量执行。
 */
@Entity
@Table(name = "test_suites")
@Data
public class TestSuite {

    @Id
    private String id;

    private String projectId;

    private String name;

    /** 用例 ID 数组 JSON */
    @Column(columnDefinition = "TEXT")
    private String caseIds = "[]";

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
