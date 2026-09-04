package com.testagent.agent;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * v13.3(①)(③): 点击即时快照存取 + LLM 裁决解析测试。
 */
class ExecutionAgentLlmJudgeTest {

    private final ExecutionAgent agent = new ExecutionAgent();

    // ==================== ① 动作快照 ====================

    @Test
    void snapshotConsumedByImmediatelyFollowingAssertStep() {
        agent.rememberActionSnapshot("exec-1", 8, "浏览足迹\n请先选择");
        String text = agent.consumeActionSnapshot("exec-1", 9);
        org.junit.jupiter.api.Assertions.assertEquals("浏览足迹\n请先选择", text);
        // 取走即失效（防同一次快照被后续断言重复消费）
        assertNull(agent.consumeActionSnapshot("exec-1", 10));
    }

    @Test
    void snapshotDiscardedWhenAssertStepNotAdjacent() {
        agent.rememberActionSnapshot("exec-2", 5, "已取消收藏");
        // 隔了多步的旧 toast 不可信 → 丢弃
        assertNull(agent.consumeActionSnapshot("exec-2", 9));
    }

    @Test
    void blankSnapshotNotStored() {
        agent.rememberActionSnapshot("exec-3", 1, "  ");
        assertNull(agent.consumeActionSnapshot("exec-3", 2));
        agent.rememberActionSnapshot(null, 1, "x");
        assertNull(agent.consumeActionSnapshot(null, 2));
    }

    // ==================== ③ LLM 裁决解析 ====================

    @Test
    void parseVerdictJson() {
        String[] r = agent.parseLlmVerdict(
                "{\"verdict\":\"passed\",\"reason\":\"页面文本以浏览足迹开头，已是足迹列表\"}");
        assertArrayEquals(new String[]{"passed", "页面文本以浏览足迹开头，已是足迹列表"}, r);
    }

    @Test
    void parseVerdictWithSurroundingText() {
        String[] r = agent.parseLlmVerdict(
                "判定结果：{\"verdict\": \"failed\", \"reason\": \"未出现提示\"} 以上。");
        assertArrayEquals(new String[]{"failed", "未出现提示"}, r);
    }

    @Test
    void invalidVerdictReturnsNull() {
        assertNull(agent.parseLlmVerdict("{\"verdict\":\"ok\"}"));
        assertNull(agent.parseLlmVerdict("页面看起来没问题"));
        assertNull(agent.parseLlmVerdict(null));
        assertNull(agent.parseLlmVerdict(""));
    }

    // ==================== ③ LLM 未配置时不复核（llmJudgeExpected 返回 null） ====================

    @Test
    void judgeSkippedWhenLlmNotConfigured() {
        // llmService 未注入（null）→ isConfigured 内部应安全返回 false，llmJudgeExpected 返回 null
        // 注意：llmService 为 null 时 isConfigured 会 NPE，llmJudgeExpected 捕获所有异常 → null
        Map<String, String> pageState = new HashMap<>();
        pageState.put("textSnippet", "浏览足迹");
        assertNull(agent.llmJudgeExpected("页面显示'请先选择'提示", pageState));
    }
}
