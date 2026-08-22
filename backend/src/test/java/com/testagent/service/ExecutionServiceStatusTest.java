package com.testagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v7.2(R10): 执行终态判定单测——全 skipped 不再记 passed。
 * 背景：纯 api_call 用例 10 步全跳过，旧逻辑挂 passed 徽章，
 * 报告却是"通过 0/失败 0/跳过 10, 通过率 0%"，同屏自相矛盾。
 */
class ExecutionServiceStatusTest {

    @Test
    void allSkippedIsSkippedNotPassed() {
        // R10 核心回归：10 步全跳过 → skipped（不再是 passed）
        assertEquals("skipped", ExecutionService.determineStatus(0, 0, 10));
    }

    @Test
    void anyFailedIsFailed() {
        assertEquals("failed", ExecutionService.determineStatus(5, 1, 4));
        assertEquals("failed", ExecutionService.determineStatus(0, 3, 0));
    }

    @Test
    void passedWithNoFailedIsPassed() {
        assertEquals("passed", ExecutionService.determineStatus(8, 0, 2));
        assertEquals("passed", ExecutionService.determineStatus(1, 0, 0));
    }

    @Test
    void skippedWithSomePassedIsStillPassed() {
        // 部分通过 + 部分跳过：有真实通过证据 → passed（跳过只是无法判定的步骤）
        assertEquals("passed", ExecutionService.determineStatus(3, 0, 7));
    }

    @Test
    void noStepsNoErrorRemainsPassed() {
        // 既有语义保持：无步骤且无错误（理论上不可达，errorMessage 在调用方已折算为 failed）
        assertEquals("passed", ExecutionService.determineStatus(0, 0, 0));
    }
}
