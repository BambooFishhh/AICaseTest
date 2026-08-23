package com.testagent.agent;

import com.testagent.entity.TestCase;
import com.testagent.service.LlmService;
import com.testagent.service.PromptSkillLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v7.12(R15): reject 比例分母单测——分母 = 已评审数（byIndex.size()），非送评总数。
 * 旧实现分母含缺评条目：20 条送评、10 条有输出且全 reject（真实 100%，应触发全保留保护带）
 * 被稀释成 50%（灰区，高置信照删）。缺评保护由 R4 补评负责，两道防线各司其职。
 */
class TestCaseReviewAgentRejectRatioTest {

    private TestCaseReviewAgent agent;
    private LlmService llmService;

    @BeforeEach
    void setUp() {
        agent = new TestCaseReviewAgent();
        llmService = mock(LlmService.class);
        ReflectionTestUtils.setField(agent, "llmService", llmService);
        ReflectionTestUtils.setField(agent, "promptSkillLoader", new PromptSkillLoader());
    }

    private TestCase caseOf(String title) {
        TestCase tc = new TestCase();
        tc.setTitle(title);
        tc.setModule("M");
        tc.setType("positive");
        tc.setPriority("P1");
        tc.setStructuredSteps("[{\"order\":1,\"type\":\"navigate\",\"action\":\"打开页面\"}]");
        tc.setExecutionHints("{}");
        return tc;
    }

    private List<TestCase> cases(int n) {
        List<TestCase> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(caseOf("c" + i));
        }
        return list;
    }

    /** 前 Reviewed 条全部 reject（高置信）的响应 JSON */
    private String allRejectJson(int reviewed) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < reviewed; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"index\":").append(i)
                    .append(",\"status\":\"reject\",\"confidence\":0.9,\"issues\":[]}");
        }
        return sb.append("]").toString();
    }

    private List<String> titles(List<TestCase> result) {
        return result.stream().map(TestCase::getTitle).collect(Collectors.toList());
    }

    @Test
    void truncatedBulkRejectNotDilutedByMissing() throws Exception {
        // 20 条送评：首轮只输出前 10 条（全 reject 高置信），补评轮仍无输出（截断）
        // 旧分母（20）：10/20=50% 灰区 → 高置信 reject 照删 10 条
        // 新分母（10）：10/10=100% > 70% → 判可疑全保留
        when(llmService.chat(anyString(), anyString(), anyDouble()))
                .thenReturn(allRejectJson(10))   // 首轮：仅 0-9 有输出
                .thenReturn("[]");               // 补评轮：仍缺

        List<TestCase> result = agent.llmReview(cases(20), Map.of(), "generation");

        assertEquals(20, result.size(), "已评审条目全 reject → 真实比例 100%，不得被缺评条目稀释成灰区照删");
    }

    @Test
    void fullReviewBulkRejectKeepsAll() throws Exception {
        // 无缺评场景回归：10/10 全 reject → 100% > 70% 全保留（分母变化不改变该结论）
        when(llmService.chat(anyString(), anyString(), anyDouble()))
                .thenReturn(allRejectJson(10));

        List<TestCase> result = agent.llmReview(cases(10), Map.of(), "generation");

        assertEquals(10, result.size(), "全量评审全 reject 应触发可疑保护带");
    }

    @Test
    void lowRejectRatioStillDeleted() throws Exception {
        // 10 条全有输出、3 条 reject → 3/10=30% ≤ 40% 照删（分母口径修改不影响正常淘汰）
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            if (i > 0) {
                sb.append(",");
            }
            String status = i < 3 ? "reject" : "pass";
            sb.append("{\"index\":").append(i).append(",\"status\":\"").append(status)
                    .append("\",\"confidence\":0.9,\"issues\":[]}");
        }
        when(llmService.chat(anyString(), anyString(), anyDouble()))
                .thenReturn(sb.append("]").toString());

        List<TestCase> result = agent.llmReview(cases(10), Map.of(), "generation");

        assertEquals(List.of("c3", "c4", "c5", "c6", "c7", "c8", "c9"), titles(result),
                "30% reject 属正常淘汰区间，应照删");
    }
}
