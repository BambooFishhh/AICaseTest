package com.testagent.agent;

import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.entity.StateMachine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v13.20: 覆盖清单的 PRD 依据过滤单测。
 *
 * <p>背景：checklist 的 transitions 取自代码状态机的**全部**转换，进而进入 gaps；
 * 而 roundNote 会要求"优先为这些缺口生成用例"。这跟证据对账对 CODE_ONLY 判 skip
 * （无 PRD 依据不生成用例）直接矛盾——同一 prompt 内两条互斥指令。
 *
 * <p>本次修正：只把"两端状态均未被 PRD 状态流描述"的转换排除出清单。
 *
 * <p>两条必须守住的边界：
 * <ol>
 *   <li><b>PRD 无状态流时不过滤</b>——与对账侧"证据缺失 ≠ 冲突"同一原则，
 *       否则 PRD 未解析出 states 的项目会丢掉全部状态流转覆盖；</li>
 *   <li><b>只排除两端皆无依据者</b>——一端被 PRD 提到就保守保留，避免
 *       同义不同名（PRD"待支付" vs 代码 PENDING_PAYMENT）误伤。</li>
 * </ol>
 */
class TestGeneratorAgentChecklistPrdScopeTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> transitions(PrdAnalysisResult prd, List<StateMachine> sms) {
        Map<String, Object> coverage = agent.buildCoverageChecklist(prd, sms, null);
        Map<String, Object> checklist = (Map<String, Object>) coverage.get("checklist");
        return (List<Map<String, Object>>) checklist.get("transitions");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> gaps(PrdAnalysisResult prd, List<StateMachine> sms) {
        Map<String, Object> coverage = agent.buildCoverageChecklist(prd, sms, null);
        return (Map<String, Object>) coverage.get("gaps");
    }

    private PrdAnalysisResult prdWithFlows(Object... flows) {
        PrdAnalysisResult prd = new PrdAnalysisResult();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object f : flows) {
            list.add((Map<String, Object>) f);
        }
        prd.setStateFlows(list);
        return prd;
    }

    private Map<String, Object> flow(String name, Object states) {
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("name", name);
        flow.put("states", states);
        return flow;
    }

    private StateMachine stateMachine(String transitionsJson) {
        StateMachine sm = new StateMachine();
        sm.setName("订单状态机");
        sm.setTransitions(transitionsJson);
        return sm;
    }

    @Test
    void codeOnlyTransitionIsExcludedFromChecklist() {
        PrdAnalysisResult prd = prdWithFlows(flow("订单流转", List.of("待支付", "已支付", "已取消")));
        StateMachine sm = stateMachine("[{\"from\":\"待支付\",\"to\":\"已支付\"},"
                + "{\"from\":\"INIT\",\"to\":\"DELETED\"}]");

        List<Map<String, Object>> items = transitions(prd, List.of(sm));

        assertEquals(1, items.size(), "代码独有转换（INIT->DELETED）应被排除");
        assertEquals("待支付->已支付", items.get(0).get("id"));
    }

    @Test
    void excludedTransitionAlsoLeavesGaps() {
        PrdAnalysisResult prd = prdWithFlows(flow("订单流转", List.of("待支付", "已支付")));
        StateMachine sm = stateMachine("[{\"from\":\"待支付\",\"to\":\"已支付\"},"
                + "{\"from\":\"INIT\",\"to\":\"DELETED\"}]");

        List<String> ids = (List<String>) gaps(prd, List.of(sm)).get("transitionIds");

        assertEquals(List.of("待支付->已支付"), ids, "被排除的转换不得留在缺口清单里驱动补齐");
    }

    @Test
    void transitionWithEitherEndpointInPrdIsKept() {
        // 一端被 PRD 提到即保留——PRD 写"待支付→已支付"，代码多了一个中间态
        // "支付中"。严格判"两端都在 PRD"会误伤此类真实流转。
        PrdAnalysisResult prd = prdWithFlows(flow("订单流转", List.of("待支付", "已支付")));
        StateMachine sm = stateMachine("[{\"from\":\"待支付\",\"to\":\"支付中\"}]");

        List<Map<String, Object>> items = transitions(prd, List.of(sm));

        assertEquals(1, items.size(), "一端有 PRD 依据的转换应保留");
        assertEquals("待支付->支付中", items.get(0).get("id"));
    }

    @Test
    void prdWithoutStateFlowsKeepsAllTransitions() {
        // 回归保护：PRD 未解析出状态流时不得过滤（否则整套状态流转覆盖消失）
        StateMachine sm = stateMachine("[{\"from\":\"INIT\",\"to\":\"DELETED\"}]");

        assertEquals(1, transitions(null, List.of(sm)).size(), "prdResult 为 null 时不过滤");

        PrdAnalysisResult empty = new PrdAnalysisResult();
        empty.setStateFlows(null);
        assertEquals(1, transitions(empty, List.of(sm)).size(), "stateFlows 为 null 时不过滤");

        PrdAnalysisResult blank = new PrdAnalysisResult();
        blank.setStateFlows(new ArrayList<>());
        assertEquals(1, transitions(blank, List.of(sm)).size(), "stateFlows 为空列表时不过滤");
    }

    @Test
    void stateFlowsWithoutStatesDoNotFilterEverything() {
        // 有状态流条目但 states 缺失/为空 → 状态池为空 → 必须保持原行为
        PrdAnalysisResult prd = prdWithFlows(flow("订单流转", null), flow("退款流转", new ArrayList<>()));
        StateMachine sm = stateMachine("[{\"from\":\"INIT\",\"to\":\"DELETED\"}]");

        assertEquals(1, transitions(prd, List.of(sm)).size(),
                "PRD 有状态流条目但无 states 时，状态池为空 → 不过滤");
    }

    @Test
    void stateNameComparisonIgnoresCaseAndWhitespace() {
        PrdAnalysisResult prd = prdWithFlows(flow("订单流转", List.of(" Paid ", "PENDING")));
        StateMachine sm = stateMachine("[{\"from\":\"paid\",\"to\":\"PAID_DONE\"}]");

        List<Map<String, Object>> items = transitions(prd, List.of(sm));

        assertEquals(1, items.size(), "归一化（trim + 小写）后 'paid' 应命中 PRD 的 ' Paid '");
    }

    @Test
    void objectFormStatesAreCollected() {
        // states 元素可能是 {name, code} 对象（与 OrchestratorAgent.readFlowStates 同口径）
        Map<String, Object> stateObj = new LinkedHashMap<>();
        stateObj.put("name", "待支付");
        stateObj.put("code", "PENDING_PAYMENT");
        PrdAnalysisResult prd = prdWithFlows(flow("订单流转", List.of(stateObj)));
        StateMachine sm = stateMachine("[{\"from\":\"待支付\",\"to\":\"已支付\"}]");

        assertEquals(1, transitions(prd, List.of(sm)).size(), "对象形式的 states 应被收集");
    }

    @Test
    void collectPrdStatesReadsBothStringAndObjectForms() {
        Map<String, Object> stateObj = new LinkedHashMap<>();
        stateObj.put("code", "PAID");
        PrdAnalysisResult prd = prdWithFlows(flow("流转", List.of("待支付", stateObj)));

        var states = agent.collectPrdStates(prd);

        assertTrue(states.contains("待支付"));
        assertTrue(states.contains("paid"), "name 缺失时回落到 code");
        assertEquals(2, states.size());
    }

    @Test
    void collectPrdStatesReturnsEmptyForMissingData() {
        assertTrue(agent.collectPrdStates(null).isEmpty());
        PrdAnalysisResult prd = new PrdAnalysisResult();
        assertTrue(agent.collectPrdStates(prd).isEmpty(), "stateFlows 为 null 时返回空集");
    }

    @Test
    void endpointChecklistIsNotAffectedByPrdScopeFilter() {
        // 边界记录：本次只收口 transitions。endpoints 仍取自代码分析——
        // PRD 没有"接口清单"概念可对齐，强行过滤会误伤，故保持现状（靠 prompt 声明约束）。
        EndpointInfo ep = EndpointInfo.builder().method("GET").path("/api/orders").build();
        BackendResult backend = new BackendResult();
        backend.setEndpoints(List.of(ep));
        PrdAnalysisResult prd = prdWithFlows(flow("订单流转", List.of("待支付")));

        Map<String, Object> coverage = agent.buildCoverageChecklist(prd, null, backend);
        @SuppressWarnings("unchecked")
        Map<String, Object> checklist = (Map<String, Object>) coverage.get("checklist");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> eps = (List<Map<String, Object>>) checklist.get("endpoints");

        assertEquals(1, eps.size(), "endpoints 不受本次过滤影响");
        assertEquals("GET /api/orders", eps.get(0).get("id"));
        assertFalse(eps.isEmpty());
    }
}
