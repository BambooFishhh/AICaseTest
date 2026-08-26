package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * v8.6.1(9.3): Milvus↔MySQL 周期对账报告——逐项目记录漂移与修复结果。
 */
@Entity
@Table(name = "reconciliation_reports")
public class ReconciliationReport {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_REPAIRED = "REPAIRED";
    public static final String STATUS_WARN = "WARN";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "db_count", nullable = false)
    private long dbCount;

    @Column(name = "vec_count", nullable = false)
    private long vecCount;

    @Column(name = "drift_ratio", nullable = false)
    private double driftRatio;

    @Column(name = "repaired_added", nullable = false)
    private int repairedAdded = 0;

    @Column(name = "repaired_removed", nullable = false)
    private int repairedRemoved = 0;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public long getDbCount() { return dbCount; }
    public void setDbCount(long dbCount) { this.dbCount = dbCount; }
    public long getVecCount() { return vecCount; }
    public void setVecCount(long vecCount) { this.vecCount = vecCount; }
    public double getDriftRatio() { return driftRatio; }
    public void setDriftRatio(double driftRatio) { this.driftRatio = driftRatio; }
    public int getRepairedAdded() { return repairedAdded; }
    public void setRepairedAdded(int repairedAdded) { this.repairedAdded = repairedAdded; }
    public int getRepairedRemoved() { return repairedRemoved; }
    public void setRepairedRemoved(int repairedRemoved) { this.repairedRemoved = repairedRemoved; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
