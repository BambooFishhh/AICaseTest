package com.testagent.agent;

import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.10(G8): 流式解析单解析真源单测——旧实现流式回调推一遍、
 * 完整响应再全量解析一遍按 parsedCount 索引补推，两边索引错位导致重复推/漏推。
 * 新实现 StreamingTestCaseParser.collected 是唯一返回源，调用方直接取收集结果。
 */
class TestGeneratorAgentStreamParseTest {

    private TestGeneratorAgent.StreamingTestCaseParser newParser(List<TestCase> collected) {
        TestGeneratorAgent agent = new TestGeneratorAgent();
        return agent.new StreamingTestCaseParser(collected::add);
    }

    @Test
    void collectedMatchesParsedCountForCompleteArray() {
        List<TestCase> collected = new ArrayList<>();
        var parser = newParser(collected);

        parser.append("[{\"title\":\"A\",\"module\":\"M\",\"type\":\"positive\"},"
                + "{\"title\":\"B\",\"module\":\"M\",\"type\":\"negative\"},"
                + "{\"title\":\"C\",\"module\":\"M\",\"type\":\"boundary\"}]");
        parser.finish();

        assertEquals(3, parser.getParsedCount());
        assertEquals(3, parser.getCollected().size(), "收集列表与解析计数一致（单解析真源）");
        assertEquals("C", parser.getCollected().get(2).getTitle());
    }

    @Test
    void chunkedAppendProducesSameCollectedAsSingleAppend() {
        // 同一 JSON 按 7 字符分块流式喂入 → 收集结果与一次性喂入完全一致（无错位/丢失）
        String json = "[{\"title\":\"A\",\"module\":\"M\",\"type\":\"positive\"},"
                + "{\"title\":\"B\",\"module\":\"M\",\"type\":\"negative\"}]";

        List<TestCase> chunked = new ArrayList<>();
        var chunkParser = newParser(chunked);
        for (int i = 0; i < json.length(); i += 7) {
            chunkParser.append(json.substring(i, Math.min(i + 7, json.length())));
        }
        chunkParser.finish();

        List<TestCase> single = new ArrayList<>();
        var singleParser = newParser(single);
        singleParser.append(json);
        singleParser.finish();

        assertEquals(single.size(), chunked.size());
        for (int i = 0; i < single.size(); i++) {
            assertEquals(single.get(i).getTitle(), chunked.get(i).getTitle(),
                    "第 " + i + " 条用例分块/一次性解析结果应一致");
        }
    }

    @Test
    void truncatedLastObjectRecoveredIntoCollected() {
        List<TestCase> collected = new ArrayList<>();
        var parser = newParser(collected);

        // 第二条在 type 字段后被截断（L8 抢救语义不变），抢救条目也应进入 collected
        parser.append("[{\"title\":\"A\",\"module\":\"M\",\"type\":\"positive\"},"
                + "{\"title\":\"B\",\"module\":\"订单\",\"ty");
        assertTrue(parser.finish(), "应检测到截断");

        assertEquals(2, parser.getCollected().size(), "完整第一条 + 抢救的第二条都进入收集列表");
        assertEquals(1, parser.getRecovered());
        assertEquals("B", parser.getCollected().get(1).getTitle());
    }

    @Test
    void emptyBufferCollectsNothing() {
        List<TestCase> collected = new ArrayList<>();
        var parser = newParser(collected);
        parser.finish();

        assertEquals(0, parser.getParsedCount());
        assertTrue(parser.getCollected().isEmpty(), "0 解析时收集列表为空（调用方走全量重解析兜底）");
    }
}
