package com.testagent.agent;

import com.testagent.dto.EvidenceConflict;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.entity.Project;
import com.testagent.entity.StateMachine;
import com.testagent.repository.CodeAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v7.10(C2) → v13.17: 证据链对账单测。
 * ① 新鲜度：需求资料（project.updatedAt）晚于代码侧最新产物（CodeAnalysis/StateMachine.createdAt）
 *    → evidenceStale=true + SSE 提示"建议重新分析"（提示语义为建议非阻断）。
 * ② 一致性：状态流冲突 → 结构化 EvidenceConflict，权威由判定矩阵按
 *    （需求状态 × 冲突方向 × 新鲜度）给出：
 *      - 新增需求（代码在 PRD 之后实现，可能跑偏）→ 以 PRD 为准
 *      - 变更中需求 → 人工裁决
 *      - 存量稳定 + 代码有 PRD 无 → 以代码为准（自动采信，不进清单）
 *      - 存量稳定 + PRD 有代码无 → 人工裁决
 *    任一侧证据缺失不判（不误报）。
 */
class OrchestratorAgentConsistencyTest {

    private OrchestratorAgent agent;
    private CodeAnalysisRepository codeAnalysisRepository;

    @BeforeEach
    void setUp() {
        agent = new OrchestratorAgent();
        codeAnalysisRepository = mock(CodeAnalysisRepository.class);
        when(codeAnalysisRepository.findFirstByProjectIdOrderByCreatedAtDesc(anyString()))
                .thenReturn(Optional.empty());
        ReflectionTestUtils.setField(agent, "codeAnalysisRepository", codeAnalysisRepository);
    }

    private StateMachine smWithStates(String name, String statesJson) {
        StateMachine sm = new StateMachine();
        sm.setName(name);
        sm.setStates(statesJson);
        return sm;
    }

    private PrdAnalysisResult prdWithFlow(String flowName, String... states) {
        PrdAnalysisResult prd = new PrdAnalysisResult();
        List<Map<String, Object>> flows = new ArrayList<>();
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("name", flowName);
        flow.put("states", List.of(states));
        flows.add(flow);
        prd.setStateFlows(flows);
        return prd;
    }

    private StateMachine orderStateMachine() {
        return smWithStates("OrderStateMachine",
                "[{\"name\":\"已创建\",\"code\":\"CREATED\"},{\"name\":\"已支付\",\"code\":\"PAID\"}]");
    }

    // ==================== ② 状态流一致性：触发阈值 ====================

    @Test
    void zeroHitFlowIsMarkedAsInconsistency() {
        PrdAnalysisResult prd = prdWithFlow("退款流程", "已申请", "审核中", "已退款");

        agent.applyStateFlowConsistency(prd, List.of(orderStateMachine()),
                EvidenceAuthorityResolver.STATE_NEW, false, null);

        List<String> conflicts = prd.getEvidenceInconsistencies();
        assertEquals(1, conflicts.size(), "PRD 状态流零命中应记冲突");
        assertTrue(conflicts.get(0).contains("退款流程"), "冲突项应含流程名");
        assertTrue(conflicts.get(0).contains("已申请/审核中/已退款"), "冲突项应含 PRD 侧状态");
        assertTrue(conflicts.get(0).contains("以 PRD 为准"),
                "新增需求下 PRD 有代码无：以 PRD 为准生成（v13.21 起存量稳定该场景不再提请人工）");
    }

    @Test
    void partiallyHitFlowIsNotConflict() {
        // PRD 流程的状态有一个命中代码状态机 → 不触发冲突（字符串匹配误报率高，阈值保持链级零命中）
        PrdAnalysisResult prd = prdWithFlow("支付流程", "已创建", "待支付", "已支付");

        agent.applyStateFlowConsistency(prd, List.of(orderStateMachine()),
                EvidenceAuthorityResolver.STATE_STABLE, false, null);

        assertNull(prd.getEvidenceInconsistencies(), "部分命中不应记冲突");
    }

    @Test
    void codeStateMatchIsCaseInsensitiveOnCode() {
        // PRD 用 code 值（PAID）与代码状态机 name（已支付）/code（PAID）匹配——归一化小写
        PrdAnalysisResult prd = prdWithFlow("支付流程", "CREATED", "PAID");

        agent.applyStateFlowConsistency(prd, List.of(orderStateMachine()),
                EvidenceAuthorityResolver.STATE_STABLE, false, null);

        assertNull(prd.getEvidenceInconsistencies(), "code 值大小写归一化后应命中");
    }

    @Test
    void noCodeStateMachineSkipsCheck() {
        // 无代码状态机 → 证据缺失 ≠ 冲突，不误报
        PrdAnalysisResult prd = prdWithFlow("任意流程", "状态A", "状态B");

        agent.applyStateFlowConsistency(prd, List.of(),
                EvidenceAuthorityResolver.STATE_STABLE, false, null);

        assertNull(prd.getEvidenceInconsistencies());
    }

    @Test
    void noStateFlowsSkipsCheck() {
        PrdAnalysisResult prd = new PrdAnalysisResult();
        prd.setStateFlows(new ArrayList<>());

        agent.applyStateFlowConsistency(prd, List.of(smWithStates("SM", "[{\"code\":\"A\"}]")),
                EvidenceAuthorityResolver.STATE_STABLE, false, null);

        assertNull(prd.getEvidenceInconsistencies());
    }

    // ==================== ② 状态流一致性：权威判定矩阵 ====================

    @Test
    void newRequirementPrdOnlyConflictIsPrdAuthoritative() {
        // 本期新增需求：代码在 PRD 之后才实现，可能未充分理解业务意图 → 以 PRD 为准（不再以代码为准）
        PrdAnalysisResult prd = prdWithFlow("退款流程", "已申请", "审核中", "已退款");

        agent.applyStateFlowConsistency(prd, List.of(orderStateMachine()),
                EvidenceAuthorityResolver.STATE_NEW, false, null);

        EvidenceConflict conflict = prd.getEvidenceConflicts().get(0);
        assertEquals(EvidenceAuthorityResolver.AUTH_PRD, conflict.getAuthority(), "新增需求应以 PRD 为准");
        assertFalse(conflict.isManualRequired(), "未实现属新增需求的预期内，无需人工裁决");
        assertTrue(conflict.getReason().contains("以 PRD 为准"));
        assertEquals(EvidenceAuthorityResolver.DIR_PRD_ONLY, conflict.getDirection());
        assertEquals("退款流程", conflict.getAnchor(), "冲突应锚定到具体状态流");
    }

    @Test
    void modifiedRequirementConflictIsDeferredNotReported() {
        // v13.19: 需求变更中 → 暂缓（PRD 尚未定稿）。既不提请人工，也不作为生成依据上报
        PrdAnalysisResult prd = prdWithFlow("退款流程", "已申请", "审核中");

        agent.applyStateFlowConsistency(prd, List.of(orderStateMachine()),
                EvidenceAuthorityResolver.STATE_MODIFIED, false, null);

        assertNull(prd.getEvidenceConflicts(), "变更中需求一律暂缓，等 PRD 定稿后重跑，不打扰人");
    }

    @Test
    void stalePrdOnlyConflictIsPrdAuthoritative() {
        // 无本期范围数据的项目靠时序兜底：PRD 更新晚于代码 + PRD 有代码无 → 未实现，以 PRD 为准
        PrdAnalysisResult prd = prdWithFlow("退款流程", "已申请");

        agent.applyStateFlowConsistency(prd, List.of(smWithStates("SM", "[{\"code\":\"CREATED\"}]")),
                EvidenceAuthorityResolver.STATE_STABLE, true, null);

        EvidenceConflict conflict = prd.getEvidenceConflicts().get(0);
        assertEquals(EvidenceAuthorityResolver.AUTH_PRD, conflict.getAuthority(),
                "PRD 更新晚于代码且元素未实现，按新增需求处理");
    }

    @Test
    void codeOnlyConflictIsNotReportedWhenRequirementStable() {
        // 存量稳定 + 代码有 PRD 无 = 文档滞后；无需求依据 → 不生成用例，也不进清单（不打扰人）
        PrdAnalysisResult prd = prdWithFlow("支付流程", "已创建");

        agent.applyStateFlowConsistency(prd, List.of(orderStateMachine()),
                EvidenceAuthorityResolver.STATE_STABLE, false, null);

        assertNull(prd.getEvidenceInconsistencies(),
                "无需求依据的冲突不作为生成依据，不应进入用户可见清单");
    }

    @Test
    void codeOnlyConflictIsSkippedForEveryRequirementState() {
        // v13.19: 用例只来源于 PRD —— 代码多出的状态没有需求依据，
        // 无论新增还是存量一律不作为生成依据上报（仅记录为文档缺口），更不得自动"以代码为准"
        for (String state : List.of(EvidenceAuthorityResolver.STATE_NEW,
                EvidenceAuthorityResolver.STATE_STABLE)) {
            PrdAnalysisResult prd = prdWithFlow("支付流程", "已创建");

            agent.applyStateFlowConsistency(prd, List.of(orderStateMachine()), state, false, null);

            assertNull(prd.getEvidenceConflicts(),
                    "代码有 PRD 无不应作为生成依据上报: " + state);
        }
    }

    @Test
    void conflictCarriesStableIdAcrossRuns() {
        // 同一冲突跨轮次 id 一致（content hash），供裁决规则回写与命中
        PrdAnalysisResult first = prdWithFlow("退款流程", "已申请");
        PrdAnalysisResult second = prdWithFlow("退款流程", "已申请");
        List<StateMachine> sms = List.of(orderStateMachine());

        agent.applyStateFlowConsistency(first, sms, EvidenceAuthorityResolver.STATE_NEW, false, null);
        agent.applyStateFlowConsistency(second, sms, EvidenceAuthorityResolver.STATE_NEW, false, null);

        assertEquals(first.getEvidenceConflicts().get(0).getConflictId(),
                second.getEvidenceConflicts().get(0).getConflictId());
    }

    // ==================== ① 证据新鲜度 ====================

    private Project projectUpdatedAt(LocalDateTime updatedAt) {
        Project project = new Project();
        project.setId("p1");
        project.setUpdatedAt(updatedAt);
        return project;
    }

    private StateMachine smCreatedAt(LocalDateTime createdAt) {
        StateMachine sm = new StateMachine();
        sm.setName("SM");
        sm.setStates("[]");
        sm.setCreatedAt(createdAt);
        return sm;
    }

    @Test
    void requirementUpdatedAfterCodeSideIsStale() {
        Project project = projectUpdatedAt(LocalDateTime.of(2026, 8, 23, 12, 0));
        List<StateMachine> sms = List.of(smCreatedAt(LocalDateTime.of(2026, 8, 23, 10, 0)));
        PrdAnalysisResult prd = new PrdAnalysisResult();
        List<String> messages = new ArrayList<>();

        agent.applyEvidenceStaleness(project, sms, prd, messages::add);

        assertTrue(prd.isEvidenceStale(), "需求资料晚于代码侧 → stale");
        assertEquals(1, messages.size(), "应推送 SSE 建议");
        assertTrue(messages.get(0).contains("建议重新分析"), "提示语为建议非阻断");
    }

    @Test
    void requirementUpdatedBeforeCodeSideIsNotStale() {
        Project project = projectUpdatedAt(LocalDateTime.of(2026, 8, 23, 8, 0));
        List<StateMachine> sms = List.of(smCreatedAt(LocalDateTime.of(2026, 8, 23, 10, 0)));
        PrdAnalysisResult prd = new PrdAnalysisResult();
        List<String> messages = new ArrayList<>();

        agent.applyEvidenceStaleness(project, sms, prd, messages::add);

        assertFalse(prd.isEvidenceStale(), "需求资料早于代码侧 → 非 stale");
        assertTrue(messages.isEmpty());
    }

    @Test
    void missingTimestampsSkipStalenessCheck() {
        // 代码侧无时间戳 → 证据缺失不判 stale（不误报）
        Project project = projectUpdatedAt(LocalDateTime.of(2026, 8, 23, 12, 0));
        PrdAnalysisResult prd = new PrdAnalysisResult();
        List<String> messages = new ArrayList<>();

        agent.applyEvidenceStaleness(project, List.of(), prd, messages::add);

        assertFalse(prd.isEvidenceStale());
        assertTrue(messages.isEmpty());
    }

    // ==================== ③ 需求状态解析（无范围数据 → STABLE） ====================

    @Test
    void noScopeDataFallsBackToStable() {
        assertEquals(EvidenceAuthorityResolver.STATE_STABLE, agent.resolveRequirementState(null));
        assertEquals(EvidenceAuthorityResolver.STATE_STABLE,
                agent.resolveRequirementState(com.testagent.service.ScopeSlicingService.ScopeSlice.EMPTY));
    }
}
