package com.testagent.agent;

import com.testagent.dto.EvidenceConflict;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.entity.EvidenceConflictRecord;
import com.testagent.entity.StateMachine;
import com.testagent.repository.EvidenceConflictRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v13.17(证据权威判定): 冲突持久化与裁决规则命中单测。
 *
 * 覆盖：
 * ① 落库——新冲突以 pending 状态入库；
 * ② upsert——同一冲突重复生成走更新而非新增，且**已有人工裁决不被覆盖**；
 * ③ 规则命中——已裁决的冲突在后续生成中自动套用，不再重复提请人工；
 * ④ deprecated 裁决——既不生成对应用例、也不再打扰人。
 */
class EvidenceConflictPersistenceTest {

    private OrchestratorAgent agent;
    private Map<String, EvidenceConflictRecord> store;

    @BeforeEach
    void setUp() {
        agent = new OrchestratorAgent();
        store = new LinkedHashMap<>();
        EvidenceConflictRecordRepository repository = mock(EvidenceConflictRecordRepository.class);
        when(repository.save(any(EvidenceConflictRecord.class))).thenAnswer(inv -> {
            EvidenceConflictRecord record = inv.getArgument(0);
            store.put(record.getId(), record);
            return record;
        });
        when(repository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        // 必须忠实模拟 repository 的 WHERE project_id = ? 语义：
        // 若忽略参数返回全部，跨项目隔离会看起来"失效"，测试将得出假结论
        when(repository.findByProjectIdOrderByCreatedAtDesc(anyString())).thenAnswer(inv -> {
            String projectId = inv.getArgument(0);
            List<EvidenceConflictRecord> hits = new ArrayList<>();
            for (EvidenceConflictRecord record : store.values()) {
                if (projectId != null && projectId.equals(record.getProjectId())) {
                    hits.add(record);
                }
            }
            return hits;
        });
        ReflectionTestUtils.setField(agent, "evidenceConflictRecordRepository", repository);
    }

    private StateMachine smWithStates(String statesJson) {
        StateMachine sm = new StateMachine();
        sm.setName("OrderStateMachine");
        sm.setStates(statesJson);
        return sm;
    }

    private List<StateMachine> codeSms() {
        return List.of(smWithStates(
                "[{\"name\":\"已创建\",\"code\":\"CREATED\"},{\"name\":\"已支付\",\"code\":\"PAID\"}]"));
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

    /** 预置一条"已被人裁决"的记录（模拟前端裁决后的状态） */
    private void givenResolved(String projectId, String dimension, String anchor, String verdict) {
        String conflictKey = EvidenceConflict.buildId(dimension, anchor);
        EvidenceConflictRecord record = new EvidenceConflictRecord();
        record.setId(EvidenceConflictRecord.buildId(projectId, conflictKey));
        record.setProjectId(projectId);
        record.setConflictKey(conflictKey);
        record.setDimension(dimension);
        record.setAnchor(anchor);
        record.setDirection(EvidenceAuthorityResolver.DIR_PRD_ONLY);
        record.setRequirementState(EvidenceAuthorityResolver.STATE_STABLE);
        record.setAuthority(EvidenceAuthorityResolver.AUTH_HUMAN);
        record.setManualRequired(true);
        record.setVerdict(verdict);
        store.put(record.getId(), record);
    }

    // ==================== ① 落库 ====================

    @Test
    void newConflictIsPersistedAsPending() {
        agent.applyStateFlowConsistency(prdWithFlow("退款流程", "已申请", "审核中"),
                codeSms(), EvidenceAuthorityResolver.STATE_MODIFIED, false, "p1");

        assertNotNull(store.get(EvidenceConflictRecord.buildId("p1",
                EvidenceConflict.buildId(EvidenceConflict.DIM_STATE_FLOW, "退款流程"))),
                "冲突应落库");
        EvidenceConflictRecord record = store.values().stream()
                .filter(r -> "退款流程".equals(r.getAnchor())).findFirst().orElse(null);
        assertNotNull(record);
        assertEquals(EvidenceConflictRecord.VERDICT_PENDING, record.getVerdict(), "新冲突应为待裁决");
        assertEquals("p1", record.getProjectId());
        assertTrue(record.getPrdSide().contains("已申请"), "PRD 侧差异应序列化落库");
    }

    @Test
    void persistIsUpsertAndKeepsExistingVerdict() {
        // 第一轮生成落库 → 人工裁决为"以 PRD 为准" → 第二轮生成
        agent.applyStateFlowConsistency(prdWithFlow("退款流程", "已申请"),
                codeSms(), EvidenceAuthorityResolver.STATE_MODIFIED, false, "p1");
        store.values().forEach(r -> r.setVerdict(EvidenceConflictRecord.VERDICT_PRD));

        agent.applyStateFlowConsistency(prdWithFlow("退款流程", "已申请"),
                codeSms(), EvidenceAuthorityResolver.STATE_MODIFIED, false, "p1");

        long sameAnchor = store.values().stream().filter(r -> "退款流程".equals(r.getAnchor())).count();
        assertEquals(1, sameAnchor, "同一冲突应 upsert 而非新增记录");
        EvidenceConflictRecord record = store.values().stream()
                .filter(r -> "退款流程".equals(r.getAnchor())).findFirst().orElseThrow();
        assertEquals(EvidenceConflictRecord.VERDICT_PRD, record.getVerdict(),
                "已有人工裁决不得被新一轮生成覆盖");
    }

    // ==================== ② 规则命中 ====================

    @Test
    void resolvedAsCodeIsReusedAndStopsAskingHuman() {
        givenResolved("p1", EvidenceConflict.DIM_STATE_FLOW, "退款流程",
                EvidenceConflictRecord.VERDICT_CODE);

        PrdAnalysisResult prd = prdWithFlow("退款流程", "已申请", "审核中");
        // 用 NEW + PRD_ONLY：默认判 prd 会进 prompt，才能观察到"命中 code 规则后被拦下"。
        // （此前用 MODIFIED，而 MODIFIED 本身判 deferred 就不进清单，断言恒真、失去鉴别力）
        agent.applyStateFlowConsistency(prd, codeSms(),
                EvidenceAuthorityResolver.STATE_NEW, false, "p1");

        // 已裁决为以代码为准 → 该冲突不再进入 prompt、也不提请人工
        assertTrue(hasNoConflictFor(prd, "退款流程"), "已裁决冲突不应重复打扰人");
    }

    /** 断言结果中不含指定锚点的冲突——规则只影响它自己的锚点，其余冲突不受牵连 */
    private boolean hasNoConflictFor(PrdAnalysisResult prd, String anchor) {
        return prd.getEvidenceConflicts() == null || prd.getEvidenceConflicts().stream()
                .noneMatch(c -> anchor.equals(c.getAnchor()));
    }

    @Test
    void resolvedAsPrdIsReusedWithoutManualFlag() {
        givenResolved("p1", EvidenceConflict.DIM_STATE_FLOW, "退款流程",
                EvidenceConflictRecord.VERDICT_PRD);

        PrdAnalysisResult prd = prdWithFlow("退款流程", "已申请", "审核中");
        agent.applyStateFlowConsistency(prd, codeSms(),
                EvidenceAuthorityResolver.STATE_MODIFIED, false, "p1");

        assertNotNull(prd.getEvidenceConflicts(), "以 PRD 为准的冲突仍需注入 prompt");
        EvidenceConflict conflict = prd.getEvidenceConflicts().get(0);
        assertEquals(EvidenceAuthorityResolver.AUTH_PRD, conflict.getAuthority());
        assertTrue(!conflict.isManualRequired(), "已裁决冲突不再需要人工");
        assertTrue(conflict.getReason().contains("已按历史裁决自动套用"), "理由应标注规则来源");
    }

    @Test
    void deprecatedRuleSuppressesConflictEntirely() {
        givenResolved("p1", EvidenceConflict.DIM_STATE_FLOW, "退款流程",
                EvidenceConflictRecord.VERDICT_DEPRECATED);

        PrdAnalysisResult prd = prdWithFlow("退款流程", "已申请", "审核中");
        agent.applyStateFlowConsistency(prd, codeSms(),
                EvidenceAuthorityResolver.STATE_MODIFIED, false, "p1");

        assertTrue(hasNoConflictFor(prd, "退款流程"), "需求已废弃的冲突不应再生成用例、也不再打扰人");
    }

    @Test
    void ruleOfOtherProjectDoesNotApply() {
        givenResolved("other-project", EvidenceConflict.DIM_STATE_FLOW, "退款流程",
                EvidenceConflictRecord.VERDICT_DEPRECATED);

        PrdAnalysisResult prd = prdWithFlow("退款流程", "已申请", "审核中");
        // 用 NEW + PRD_ONLY：该冲突正常会以 PRD 为准进入 prompt，
        // 才能观察到"本项目的冲突依然在、未被别的项目的裁决影响"
        agent.applyStateFlowConsistency(prd, codeSms(),
                EvidenceAuthorityResolver.STATE_NEW, false, "p1");

        // 规则按项目隔离：别的项目的裁决不应影响本项目
        assertNotNull(prd.getEvidenceConflicts(), "跨项目规则不应命中");
        assertEquals(EvidenceAuthorityResolver.AUTH_PRD, prd.getEvidenceConflicts().get(0).getAuthority(),
                "本项目冲突仍应以 PRD 为准（未被 cross-project 的 deprecated 规则命中）");
    }

    // ==================== ③ 无仓储时不阻断 ====================

    @Test
    void missingRepositoryDoesNotBreakConfirmityCheck() {
        OrchestratorAgent bare = new OrchestratorAgent();
        PrdAnalysisResult prd = prdWithFlow("退款流程", "已申请", "审核中");

        bare.applyStateFlowConsistency(prd, codeSms(),
                EvidenceAuthorityResolver.STATE_NEW, false, "p1");

        // 仓储缺失（单测直接构造）时对账主流程照常，仅跳过持久化
        assertNotNull(prd.getEvidenceConflicts());
        assertEquals(EvidenceAuthorityResolver.AUTH_PRD, prd.getEvidenceConflicts().get(0).getAuthority());
    }
}
