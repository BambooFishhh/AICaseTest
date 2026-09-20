package com.testagent.agent;

import com.testagent.dto.EvidenceConflict;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v13.18(证据权威判定): 用例侧待裁决溯源标记——项目级快照。
 *
 * <p>锁定口径：只有 authority=human 的冲突才触发标记；无此类冲突时不写任何字段（保持 null），
 * 使"裁决后重生成 → 标记自然清除"成立。
 */
class TestGeneratorAgentVerdictTraceTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    private static EvidenceConflict conflict(String id, String dimension, String authority) {
        EvidenceConflict c = new EvidenceConflict();
        c.setConflictId(id);
        c.setDimension(dimension);
        c.setAuthority(authority);
        return c;
    }

    private static List<TestCase> cases(int n) {
        List<TestCase> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TestCase tc = new TestCase();
            tc.setTitle("用例" + i);
            list.add(tc);
        }
        return list;
    }

    private static PrdAnalysisResult prdWith(List<EvidenceConflict> conflicts) {
        PrdAnalysisResult prd = new PrdAnalysisResult();
        prd.setEvidenceConflicts(conflicts);
        return prd;
    }

    @Test
    void humanConflictMarksAllCasesAsPending() {
        List<TestCase> list = cases(3);
        PrdAnalysisResult prd = prdWith(List.of(
                conflict("ec-aaa", "STATE_FLOW", EvidenceAuthorityResolver.AUTH_HUMAN)));

        agent.markPendingVerdicts(list, prd);

        for (TestCase tc : list) {
            assertEquals("pending", tc.getVerdict(), "存在待人工裁决的冲突时该批用例应标记为待裁决");
            assertEquals("ec-aaa", tc.getConflictRef());
            assertEquals("STATE_FLOW", tc.getDimension());
        }
    }

    @Test
    void autoDecidedConflictsDoNotMarkCases() {
        List<TestCase> list = cases(2);
        // 以 PRD / 以代码为准的冲突是可自动采信的，不该打扰复核者
        PrdAnalysisResult prd = prdWith(List.of(
                conflict("ec-p1", "STATE_FLOW", EvidenceAuthorityResolver.AUTH_PRD),
                conflict("ec-p2", "STATE_FLOW", EvidenceAuthorityResolver.AUTH_CODE)));

        agent.markPendingVerdicts(list, prd);

        for (TestCase tc : list) {
            assertNull(tc.getVerdict());
            assertNull(tc.getConflictRef());
            assertNull(tc.getDimension());
        }
    }

    @Test
    void collectsDistinctRefsAndDimensionsAcrossHumanConflicts() {
        List<TestCase> list = cases(1);
        PrdAnalysisResult prd = prdWith(List.of(
                conflict("ec-aaa", "STATE_FLOW", EvidenceAuthorityResolver.AUTH_HUMAN),
                conflict("ec-bbb", "STATE_FLOW", EvidenceAuthorityResolver.AUTH_HUMAN),
                conflict("ec-ccc", "ENDPOINT", EvidenceAuthorityResolver.AUTH_HUMAN),
                conflict("ec-auto", "PAGE", EvidenceAuthorityResolver.AUTH_PRD)));

        agent.markPendingVerdicts(list, prd);

        TestCase tc = list.get(0);
        assertEquals("pending", tc.getVerdict());
        assertEquals("ec-aaa,ec-bbb,ec-ccc", tc.getConflictRef(), "只收集 human 冲突且去重保持顺序");
        assertEquals("STATE_FLOW,ENDPOINT", tc.getDimension(), "维度去重");
    }

    @Test
    void missingOrBlankConflictIdsAreSkippedNotWrittenAsNull() {
        List<TestCase> list = cases(1);
        EvidenceConflict noId = conflict(null, "STATE_FLOW", EvidenceAuthorityResolver.AUTH_HUMAN);
        EvidenceConflict blankId = conflict("  ", "STATE_FLOW", EvidenceAuthorityResolver.AUTH_HUMAN);
        PrdAnalysisResult prd = prdWith(List.of(noId, blankId));

        agent.markPendingVerdicts(list, prd);

        TestCase tc = list.get(0);
        assertEquals("pending", tc.getVerdict());
        assertEquals("", tc.getConflictRef(), "无有效 id 时拼接结果为空串，而不是字符串 \"null\"");
        assertEquals("STATE_FLOW", tc.getDimension());
    }

    @Test
    void noConflictsLeavesFieldsUntouched() {
        List<TestCase> list = cases(2);

        agent.markPendingVerdicts(list, prdWith(List.of()));
        agent.markPendingVerdicts(list, prdWith(null));
        agent.markPendingVerdicts(list, null);

        for (TestCase tc : list) {
            assertNull(tc.getVerdict());
            assertNull(tc.getConflictRef());
            assertNull(tc.getDimension());
        }
    }

    @Test
    void nullOrEmptyCaseListIsSafe() {
        PrdAnalysisResult prd = prdWith(List.of(
                conflict("ec-aaa", "STATE_FLOW", EvidenceAuthorityResolver.AUTH_HUMAN)));

        assertDoesNotThrow(() -> agent.markPendingVerdicts(null, prd));
        assertDoesNotThrow(() -> agent.markPendingVerdicts(List.of(), prd));
    }

    @Test
    void oversizedConflictRefIsCappedWithinColumnWidth() {
        List<EvidenceConflict> conflicts = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            conflicts.add(conflict(String.format("ec-%010d", i), "STATE_FLOW",
                    EvidenceAuthorityResolver.AUTH_HUMAN));
        }
        List<TestCase> list = cases(1);

        agent.markPendingVerdicts(list, prdWith(conflicts));

        String ref = list.get(0).getConflictRef();
        assertTrue(ref.length() <= 512, "conflict_ref 必须不超过列宽 512，否则整批落库会失败，实际=" + ref.length());
        assertTrue(ref.startsWith("ec-0000000000"), "截断应保留前面的项而不是清空");
        assertTrue(ref.length() > 0);
    }
}
