package com.testagent.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * v2.0: 执行步骤记录
 */
@Entity
@Table(name = "execution_step")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionStep {

    @Id
    private String id;

    private String executionId;

    private int stepIndex;

    private String action;

    private String target;

    /** visual / dom / manual / skipped */
    private String strategy;

    /** passed / failed / skipped */
    private String result;

    private String screenshotBefore;

    private String screenshotAfter;

    /** 点击坐标，如 "x=260,y=340" */
    private String coordinates;

    private String error;
}
