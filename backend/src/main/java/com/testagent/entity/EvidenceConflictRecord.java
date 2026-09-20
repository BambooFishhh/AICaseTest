package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * v13.17(证据权威判定): 证据链冲突的持久化记录。
 *
 * <p>解决的核心问题：C2 对账产出的冲突此前只存在于生成期的 {@code PrdAnalysisResult} 与 prompt 文本中，
 * 人既看不到清单、也无从裁决——机制实际是断的。本实体让冲突落库为可查询、可裁决的记录，
 * 并把裁决结果沉淀为规则供后续生成自动套用。
 *
 * <p>主键 {@code id} = {@code projectId + "-" + conflictKey}：同一项目重复生成时同一冲突（key 由
 * 维度+锚点的 content hash 决定）走 upsert 更新，**已有裁决不被覆盖**，避免裁决被新一轮生成冲掉。
 */
@Entity
@Table(name = "evidence_conflicts")
@Data
public class EvidenceConflictRecord {

    /** 待人工裁决 */
    public static final String VERDICT_PENDING = "pending";
    /** 裁决为以 PRD 为准 */
    public static final String VERDICT_PRD = "prd_authoritative";
    /** 裁决为以代码为准 */
    public static final String VERDICT_CODE = "code_authoritative";
    /** 裁决为需求已废弃（PRD 侧描述作废，不再生成对应用例） */
    public static final String VERDICT_DEPRECATED = "deprecated";

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "project_id", length = 64, nullable = false)
    private String projectId;

    /** EvidenceConflict.conflictId（维度 + 锚点 SHA-256 前 10 位），跨轮次稳定 */
    @Column(name = "conflict_key", length = 32, nullable = false)
    private String conflictKey;

    @Column(length = 32, nullable = false)
    private String dimension;

    @Column(length = 512, nullable = false)
    private String anchor;

    @Column(length = 16, nullable = false)
    private String direction;

    /** PRD 侧差异元素（JSON 数组字符串） */
    @Column(name = "prd_side", columnDefinition = "TEXT")
    private String prdSide;

    /** 代码侧差异元素（JSON 数组字符串） */
    @Column(name = "code_side", columnDefinition = "TEXT")
    private String codeSide;

    @Column(name = "requirement_state", length = 16, nullable = false)
    private String requirementState;

    private Boolean stale;

    /**
     * 权威归属：矩阵输出 {@code prd} / {@code human} / {@code skip}（无 PRD 依据不生成）
     * / {@code deferred}（需求变更中暂缓，v13.19）；命中历史裁决时改写为
     * {@code prd} / {@code code} / {@code deprecated}。
     *
     * <p>注意：本列宽度 16，只存上述短值，**不要**写入 {@code verdict} 的取值
     * （{@code prd_authoritative} 17 字符 / {@code code_authoritative} 18 字符，会截断报错）。
     * 裁决结果请写 {@code verdict} 列（宽度 24）。
     */
    @Column(length = 16, nullable = false)
    private String authority;

    @Column(name = "manual_required")
    private Boolean manualRequired;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(length = 24, nullable = false)
    private String verdict = VERDICT_PENDING;

    @Column(name = "verdict_note", length = 512)
    private String verdictNote;

    @Column(name = "verdict_by", length = 64)
    private String verdictBy;

    @Column(name = "verdict_at")
    private LocalDateTime verdictAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static String buildId(String projectId, String conflictKey) {
        return projectId + "-" + conflictKey;
    }

    /** 是否已被人工裁决（非 pending） */
    public boolean isResolved() {
        return verdict != null && !VERDICT_PENDING.equals(verdict);
    }

    /** 已裁决结果映射为权威归属；未裁决返回 null */
    public String resolvedAuthority() {
        if (VERDICT_PRD.equals(verdict)) {
            return "prd";
        }
        if (VERDICT_CODE.equals(verdict)) {
            return "code";
        }
        return null;
    }
}
