package com.testagent.agent;

import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.3(L8): 流式 JSON 截断检测与局部补全验证。
 */
class StreamingTestCaseParserTruncationTest {

    private TestGeneratorAgent.StreamingTestCaseParser newParser(List<TestCase> collected) {
        TestGeneratorAgent agent = new TestGeneratorAgent();
        return agent.new StreamingTestCaseParser(collected::add);
    }

    @Test
    void completeArrayHasNoTruncation() {
        List<TestCase> collected = new ArrayList<>();
        var parser = newParser(collected);

        parser.append("[{\"title\":\"A\",\"module\":\"M\",\"type\":\"positive\"},"
                + "{\"title\":\"B\",\"module\":\"M\",\"type\":\"negative\"}]");

        assertFalse(parser.finish(), "完整响应不应标记截断");
        assertEquals(2, parser.getParsedCount());
        assertEquals(2, collected.size());
        assertEquals(0, parser.getRecovered());
    }

    @Test
    void truncatedLastObjectIsRecovered() {
        List<TestCase> collected = new ArrayList<>();
        var parser = newParser(collected);

        // 第二条对象在 type 字段后被截断（title/module 完整）
        parser.append("[{\"title\":\"A\",\"module\":\"M\",\"type\":\"positive\"},"
                + "{\"title\":\"B\",\"module\":\"订单\",\"ty");

        assertTrue(parser.finish(), "应检测到截断");
        assertEquals(1, parser.getRecovered(), "应抢救出最后一条（字段不完整）");
        assertEquals(2, collected.size(), "第一条 + 抢救的第二条");
        assertEquals("B", collected.get(1).getTitle());
        assertEquals("订单", collected.get(1).getModule());
        assertTrue(parser.isTruncated());
    }

    @Test
    void truncatedInsideNestedArrayStillRecovers() {
        List<TestCase> collected = new ArrayList<>();
        var parser = newParser(collected);

        // 截断发生在 structuredSteps 嵌套数组内部
        parser.append("[{\"title\":\"C\",\"module\":\"M\",\"structuredSteps\":[{\"order\":1,\"acti");

        assertTrue(parser.finish());
        assertEquals(1, parser.getRecovered());
        assertEquals(1, collected.size());
        assertEquals("C", collected.get(0).getTitle());
        // 嵌套数组截断回退到最后一个安全逗号：structuredSteps 保留截断前完整的部分
        assertEquals("[{\"order\":1}]", collected.get(0).getStructuredSteps());
    }

    @Test
    void truncationWithoutSafeCommaIsDetectedButNotRecovered() {
        List<TestCase> collected = new ArrayList<>();
        var parser = newParser(collected);

        // 只有一个字段且截断在字符串值内部：无安全逗号可回退
        parser.append("[{\"title\":\"半截");

        assertTrue(parser.finish(), "截断仍应被检测并告警");
        assertEquals(0, parser.getRecovered(), "无安全截断点，不抢救");
        assertEquals(0, collected.size());
    }

    @Test
    void finishOnEmptyBufferIsNoop() {
        List<TestCase> collected = new ArrayList<>();
        var parser = newParser(collected);

        assertFalse(parser.finish());
        assertEquals(0, parser.getParsedCount());
    }
}
