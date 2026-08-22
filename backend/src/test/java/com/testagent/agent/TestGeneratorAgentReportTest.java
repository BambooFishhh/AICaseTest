package com.testagent.agent;

import com.testagent.analyzer.result.BackendResult;
import com.testagent.common.BusinessException;
import com.testagent.dto.GenerationParams;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.entity.TestCase;
import com.testagent.service.LlmService;
import com.testagent.service.PromptSkillLoader;
import com.testagent.service.SemanticService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v7.1(G2/G5/G11): GenerationReport 计数与聚焦类型错误区分单测。
 * 场景：mock LLM 每轮返回同样 3 条用例且不带 coverageRefs →
 * 需求缺口永不收敛 → medium 密度跑满 3 轮 → 后两轮全被去重删除。
 */
class TestGeneratorAgentReportTest {

    private LlmService llmService;
    private TestCaseReviewAgent reviewAgent;
    private TestGeneratorAgent agent;

    private static final String THREE_CASES_JSON = """
            [
              {"title":"登录成功验证","module":"登录","type":"positive","priority":"P0",
               "structuredSteps":[{"step":1,"action":"输入正确账号密码","element":"登录表单","expected":"进入首页"}]},
              {"title":"订单创建并支付","module":"订单","type":"positive","priority":"P0",
               "structuredSteps":[{"step":1,"action":"提交订单","element":"订单表单","expected":"支付成功"}]},
              {"title":"密码错误登录失败","module":"登录","type":"negative","priority":"P1",
               "structuredSteps":[{"step":1,"action":"输入错误密码","element":"登录表单","expected":"提示密码错误"}]}
            ]
            """;

    @BeforeEach
    void setUp() {
        llmService = mock(LlmService.class);
        reviewAgent = mock(TestCaseReviewAgent.class);
        agent = new TestGeneratorAgent();
        SemanticService semanticService = mock(SemanticService.class);
        ReflectionTestUtils.setField(agent, "llmService", llmService);
        ReflectionTestUtils.setField(agent, "testCaseReviewAgent", reviewAgent);
        ReflectionTestUtils.setField(agent, "semanticService", semanticService);
        ReflectionTestUtils.setField(agent, "promptSkillLoader", new PromptSkillLoader());
        // 评审透传（不删用例），批内语义去重透传（不删用例）
        when(reviewAgent.review(anyList(), anyMap(), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(semanticService.deduplicateBatch(anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(llmService.chat(anyString(), anyString(), anyDouble()))
                .thenReturn(THREE_CASES_JSON);
    }

    private PrdAnalysisResult prd() {
        PrdAnalysisResult prd = new PrdAnalysisResult();
        prd.setRequirements(List.of(Map.of(
                "title", "用户可以登录系统",
                "description", "输入账号密码完成登录，密码错误时提示失败")));
        return prd;
    }

    @Test
    void reportCountsReflectPipeline() {
        TestGeneratorAgent.GenerationReport report = new TestGeneratorAgent.GenerationReport();
        List<TestCase> result = agent.generate(prd(), List.of(), BackendResult.skipped(),
                null, null, null, report);

        // 3 轮（缺口不收敛）× 每轮 3 条 = 9 条生成
        assertEquals(9, report.generated);
        // 后两轮的 6 条与前轮重复，全部被标题判重删除
        assertEquals(6, report.dedupDropped);
        // 评审/语义去重被 mock 透传 → 0 丢弃
        assertEquals(0, report.reviewDropped);
        assertEquals(0, report.semanticDropped);
        // 最终 3 条，finalCount 与返回值一致
        assertEquals(3, result.size());
        assertEquals(result.size(), report.finalCount);
        // 需求缺口未收敛（req-1 从未被 coverageRefs 覆盖）→ 真实降级信号（G5）
        assertTrue(report.roundsNotConverged);
    }

    @Test
    void focusTypeFilterEmptyThrowsDistinctError() {
        // G11：已生成用例但聚焦类型过滤后为 0——错误必须与"未生成任何用例"区分
        GenerationParams params = GenerationParams.defaults();
        params.setFocusTypes(List.of("boundary"));
        TestGeneratorAgent.GenerationReport report = new TestGeneratorAgent.GenerationReport();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                agent.generate(prd(), List.of(), BackendResult.skipped(),
                        null, null, params, report));

        assertTrue(ex.getMessage().contains("聚焦类型"),
                "错误应指向聚焦类型过滤，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("过滤后为 0 条"),
                "错误应说明过滤后为 0，实际: " + ex.getMessage());
        // 报告仍然记录了生成数与类型过滤丢弃数（供 complete 事件使用）
        assertTrue(report.generated > 0);
        assertEquals(report.generated, report.focusDropped);
    }

    @Test
    void noCasesGeneratedThrowsPlainError() {
        // G11 对照组：LLM 返回空数组 → "未生成任何用例"，不含聚焦类型字样
        when(llmService.chat(anyString(), anyString(), anyDouble())).thenReturn("[]");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                agent.generate(prd(), List.of(), BackendResult.skipped(),
                        null, null, null, null));

        assertTrue(ex.getMessage().contains("未生成任何用例"),
                "实际: " + ex.getMessage());
    }
}
