package com.testagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v7.2(R10/R12): 报告通过率口径单测——分母 = passed + failed，
 * 跳过/运行中/待执行不稀释通过率（对齐 Allure 惯例）。
 */
class ReportServicePassRateTest {

    @Test
    void denominatorExcludesSkipped() {
        // 7 过 1 败 2 跳过：旧口径 7/10=70%，新口径 7/8=87.5%
        assertEquals(87.5, ReportService.passRateOf(7, 1), 0.001);
    }

    @Test
    void allSkippedYieldsZeroNotNaN() {
        // 全跳过：旧口径 0/10=0% 且状态 passed 自相矛盾；新口径无判定 → 0
        assertEquals(0.0, ReportService.passRateOf(0, 0), 0.001);
    }

    @Test
    void noRecordsYieldsZero() {
        assertEquals(0.0, ReportService.passRateOf(0, 0), 0.001);
    }

    @Test
    void batchWithRunningPendingNotDiluted() {
        // 批次报告（R12）：2 过 1 败 + 3 运行中/待执行 → 2/3≈66.7%（旧口径 2/6≈33.3%）
        assertEquals(66.666, ReportService.passRateOf(2, 1), 0.01);
    }
}
