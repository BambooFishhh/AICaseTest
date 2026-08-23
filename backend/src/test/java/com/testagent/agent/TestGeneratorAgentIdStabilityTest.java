package com.testagent.agent;

import com.testagent.dto.PrdAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.10(G7): 需求 ID 内容 hash 稳定化单测——旧实现 "req-" + i++ 是解析顺序临时编号，
 * PRD 局部修改即全量漂移（追加生成时旧用例 coverageRefs.req-3 与新 checklist 的
 * req-3 可能指向不同需求，覆盖率历史对比失真）。
 * 新 id = "req-" + SHA-256(title + '\u0001' + description) 前 10 位十六进制：
 * 同一需求内容在任意解析顺序/轮次/任务中 id 一致，局部修改只影响变更项。
 */
class TestGeneratorAgentIdStabilityTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    private Map<String, Object> req(String title, String description) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("title", title);
        r.put("description", description);
        return r;
    }

    private PrdAnalysisResult prdWith(List<Map<String, Object>> requirements) {
        PrdAnalysisResult prd = new PrdAnalysisResult();
        prd.setRequirements(requirements);
        return prd;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> checklistRequirements(PrdAnalysisResult prd) {
        Map<String, Object> coverage = agent.buildCoverageChecklist(prd, null, null);
        Map<String, Object> checklist = (Map<String, Object>) coverage.get("checklist");
        return (List<Map<String, Object>>) checklist.get("requirements");
    }

    @Test
    void contentHashIsDeterministicAndDiscriminating() {
        assertEquals(agent.contentHash("标题A", "描述A"), agent.contentHash("标题A", "描述A"),
                "同内容两次计算 hash 必须一致（跨调用/跨任务稳定）");
        assertNotEquals(agent.contentHash("标题A", "描述A"), agent.contentHash("标题A", "描述B"),
                "描述不同 → hash 不同");
        assertNotEquals(agent.contentHash("标题A", "描述A"), agent.contentHash("标题B", "描述A"),
                "标题不同 → hash 不同");
    }

    @Test
    void requirementIdIsContentHashForm() {
        List<Map<String, Object>> requirements = checklistRequirements(
                prdWith(List.of(req("登录功能", "用户可使用账号密码登录"))));

        assertEquals(1, requirements.size());
        String id = String.valueOf(requirements.get(0).get("id"));
        assertTrue(id.matches("req-[0-9a-f]{10}"),
                "id 应为 req- + 10 位十六进制 hash，实际: " + id);
    }

    @Test
    void reorderKeepsIdsStable() {
        // 两条需求调换解析顺序 → 各自 id 不变（旧 req-N 实现会整体漂移）
        List<Map<String, Object>> ab = checklistRequirements(prdWith(List.of(
                req("需求甲", "甲的描述"), req("需求乙", "乙的描述"))));
        List<Map<String, Object>> ba = checklistRequirements(prdWith(List.of(
                req("需求乙", "乙的描述"), req("需求甲", "甲的描述"))));

        Map<String, String> byTitleAb = new LinkedHashMap<>();
        for (Map<String, Object> r : ab) {
            byTitleAb.put(String.valueOf(r.get("title")), String.valueOf(r.get("id")));
        }
        for (Map<String, Object> r : ba) {
            assertEquals(byTitleAb.get(String.valueOf(r.get("title"))), String.valueOf(r.get("id")),
                    "调换顺序后同内容需求 id 必须不变: " + r.get("title"));
        }
    }

    @Test
    void localModificationOnlyAffectsChangedItem() {
        // PRD 只改第二条的描述 → 第一条 id 不变、第二条 id 变化
        List<Map<String, Object>> before = checklistRequirements(prdWith(List.of(
                req("需求一", "描述一"), req("需求二", "描述二"))));
        List<Map<String, Object>> after = checklistRequirements(prdWith(List.of(
                req("需求一", "描述一"), req("需求二", "描述二（修改后）"))));

        assertEquals(String.valueOf(before.get(0).get("id")), String.valueOf(after.get(0).get("id")),
                "未修改需求的 id 必须稳定");
        assertNotEquals(String.valueOf(before.get(1).get("id")), String.valueOf(after.get(1).get("id")),
                "修改后需求的 id 应变化");
    }

    @Test
    void duplicateContentMergedIntoSingleItem() {
        List<Map<String, Object>> requirements = checklistRequirements(prdWith(List.of(
                req("重复需求", "同描述"), req("重复需求", "同描述"))));

        assertEquals(1, requirements.size(), "同内容重复需求应合并为一条");
    }

    @Test
    void ragSliceIdIsContentHashForm() {
        PrdAnalysisResult prd = new PrdAnalysisResult();
        prd.setRequirements(new ArrayList<>());
        prd.setRagContexts(List.of("订单支付：用户在订单页点击支付按钮后跳转支付渠道"));

        List<Map<String, Object>> requirements = checklistRequirements(prd);

        assertEquals(1, requirements.size());
        String id = String.valueOf(requirements.get(0).get("id"));
        assertTrue(id.matches("rag-[0-9a-f]{10}"),
                "RAG 并入项 id 应为 rag- + 10 位十六进制 hash，实际: " + id);
        assertEquals("rag", requirements.get(0).get("source"));
    }
}
