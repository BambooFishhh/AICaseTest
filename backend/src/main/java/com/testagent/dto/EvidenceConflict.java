package com.testagent.dto;

import lombok.Data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * v13.17(证据权威判定): 结构化的证据链冲突项。
 *
 * <p>取代旧 {@code PrdAnalysisResult.evidenceInconsistencies} 的纯字符串列表——字符串无法承载
 * 维度、方向、需求状态与权威归属，导致冲突既不可定位（粒度停在整条链）也不可裁决（人工无从下手）。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code dimension}——冲突维度（STATE_FLOW / ENDPOINT / PAGE），判定粒度细化到具体维度</li>
 *   <li>{@code anchor}——需求锚点（状态流名 / 模块名 / 接口路径），用于定位到具体需求</li>
 *   <li>{@code direction}——冲突方向 PRD_ONLY（PRD 有代码无）/ CODE_ONLY（代码有 PRD 无）/ MIXED</li>
 *   <li>{@code prdSide} / {@code codeSide}——两侧差异元素明细，替代旧的"整条链命中/未命中"布尔</li>
 *   <li>{@code requirementState}——NEW / MODIFIED / STABLE，来自本期范围 changeKind</li>
 *   <li>{@code authority}——判定矩阵输出：{@code prd}（以 PRD 为准生成）/ {@code human}（需人工裁决）
 *       / {@code skip}（无 PRD 依据，不生成用例）/ {@code deferred}（需求变更中，暂缓）；
 *       另有 {@code code} / {@code deprecated} 两个非矩阵值，分别来自历史落库与人工裁决</li>
 *   <li>{@code manualRequired}——是否需人工裁决（**当且仅当** {@code authority == human}）</li>
 * </ul>
 */
@Data
public class EvidenceConflict {

    public static final String DIM_STATE_FLOW = "STATE_FLOW";
    public static final String DIM_ENDPOINT = "ENDPOINT";
    public static final String DIM_PAGE = "PAGE";

    /** 历史裁决为"需求已废弃"：不再生成对应用例，也不再提请人工（仅保留记录可查） */
    public static final String AUTHORITY_DEPRECATED = "deprecated";

    /** 稳定 id：维度 + 锚点内容 hash，同一冲突跨轮次/跨解析顺序 id 一致（沿用需求 id 的 content hash 做法） */
    private String conflictId;

    private String dimension;
    private String anchor;
    private String direction;
    private List<String> prdSide;
    private List<String> codeSide;
    private String requirementState;
    private boolean stale;
    private String authority;
    private boolean manualRequired;
    /** 人读说明，同时作为旧 evidenceInconsistencies 的条目文本（prompt 注入兼用） */
    private String reason;

    public static String buildId(String dimension, String anchor) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(((dimension == null ? "" : dimension) + "\u0001"
                    + (anchor == null ? "" : anchor)).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return "ec-" + sb;
        } catch (Exception e) {
            // 摘要算法缺失时退化为内容派生 id，不阻断对账
            return "ec-" + Integer.toHexString((String.valueOf(dimension) + String.valueOf(anchor)).hashCode());
        }
    }
}
