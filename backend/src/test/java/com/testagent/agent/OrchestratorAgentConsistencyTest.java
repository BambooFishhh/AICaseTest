package com.testagent.agent;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v7.10(C2): 证据链对账单测——PRD 与代码两条证据链无新鲜度/一致性校验时静默分叉。
 * ① 新鲜度：需求资料（project.updatedAt）晚于代码侧最新产物（CodeAnalysis/StateMachine.createdAt）
 *    → evidenceStale=true + SSE 提示"建议重新分析"（提示语义为建议非阻断）。
 * ② 一致性：PRD 状态流的全部状态在所有代码状态机中零命中 → evidenceInconsistencies 冲突项
 *    （prompt 显式标注"以代码为准，需人工确认"）。
 * 任一侧证据缺失不判（不误报）。
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

    // ==================== ② 状态流一致性 ====================

    @Test
    void zeroHitFlowIsMarkedAsInconsistency() {
        PrdAnalysisResult prd = prdWithFlow("退款流程", "已申请", "审核中", "已退款");
        List<StateMachine> codeSms = List.of(
                smWithStates("OrderStateMachine",
                        "[{\"name\":\"已创建\",\"code\":\"CREATED\"},{\"name\":\"已支付\",\"code\":\"PAID\"}]"));

        agent.applyStateFlowConsistency(prd, codeSms);

        List<String> conflicts = prd.getEvidenceInconsistencies();
        assertEquals(1, conflicts.size(), "PRD 状态流零命中应记冲突");
        assertTrue(conflicts.get(0).contains("退款流程"), "冲突项应含流程名");
        assertTrue(conflicts.get(0).contains("以代码为准"), "冲突项应标注裁决方向");
        assertTrue(conflicts.get(0).contains("已申请/审核中/已退款"), "冲突项应含 PRD 侧状态");
    }

    @Test
    void partiallyHitFlowIsNotConflict() {
        // PRD 流程的状态有一个命中代码状态机 → 不是冲突（部分印证即非静默分叉）
        PrdAnalysisResult prd = prdWithFlow("支付流程", "已创建", "待支付", "已支付");
        List<StateMachine> codeSms = List.of(
                smWithStates("OrderStateMachine",
                        "[{\"name\":\"已创建\",\"code\":\"CREATED\"},{\"name\":\"已支付\",\"code\":\"PAID\"}]"));

        agent.applyStateFlowConsistency(prd, codeSms);

        assertNull(prd.getEvidenceInconsistencies(), "部分命中不应记冲突");
    }

    @Test
    void codeStateMatchIsCaseInsensitiveOnCode() {
        // PRD 用 code 值（PAID）与代码状态机 name（已支付）/code（PAID）匹配——归一化小写包含
        PrdAnalysisResult prd = prdWithFlow("支付流程", "CREATED", "PAID");
        List<StateMachine> codeSms = List.of(
                smWithStates("OrderStateMachine",
                        "[{\"name\":\"已创建\",\"code\":\"CREATED\"},{\"name\":\"已支付\",\"code\":\"PAID\"}]"));

        agent.applyStateFlowConsistency(prd, codeSms);

        assertNull(prd.getEvidenceInconsistencies(), "code 值大小写归一化后应命中");
    }

    @Test
    void noCodeStateMachineSkipsCheck() {
        // 无代码状态机 → 证据缺失 ≠ 冲突，不误报
        PrdAnalysisResult prd = prdWithFlow("任意流程", "状态A", "状态B");

        agent.applyStateFlowConsistency(prd, List.of());

        assertNull(prd.getEvidenceInconsistencies());
    }

    @Test
    void noStateFlowsSkipsCheck() {
        PrdAnalysisResult prd = new PrdAnalysisResult();
        prd.setStateFlows(new ArrayList<>());

        agent.applyStateFlowConsistency(prd, List.of(smWithStates("SM", "[{\"code\":\"A\"}]")));

        assertNull(prd.getEvidenceInconsistencies());
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
}
