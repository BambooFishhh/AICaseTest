package com.testagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

// v8.1: 范围内元素——本期新增/变更接口、受波及状态机或手动标注项
@Entity
@Table(name = "scope_item")
@Data
public class ScopeItem {

    public static final String TYPE_ENDPOINT = "ENDPOINT";
    public static final String TYPE_STATE_MACHINE = "STATE_MACHINE";

    public static final String KIND_ADDED = "ADDED";
    public static final String KIND_MODIFIED = "MODIFIED";
    public static final String KIND_AFFECTED = "AFFECTED";

    public static final String ORIGIN_AUTO_DIFF = "AUTO_DIFF";
    public static final String ORIGIN_LLM_MAPPED = "LLM_MAPPED";
    public static final String ORIGIN_MANUAL = "MANUAL";

    @Id
    @Column(length = 8)
    private String id;

    @Column(name = "definition_id", length = 8)
    private String definitionId;

    // ENDPOINT / STATE_MACHINE
    private String itemType;

    // ENDPOINT: "GET /admin/order/list"；STATE_MACHINE: 状态机 id
    @Column(name = "item_ref", length = 512)
    private String itemRef;

    // ADDED / MODIFIED / AFFECTED
    private String changeKind;

    // AUTO_DIFF / LLM_MAPPED / MANUAL
    private String origin;

    @Column(columnDefinition = "TEXT")
    private String note = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
