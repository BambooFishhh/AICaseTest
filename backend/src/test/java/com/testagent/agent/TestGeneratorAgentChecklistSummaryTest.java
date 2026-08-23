package com.testagent.agent;

import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.analyzer.result.BusinessRule;
import com.testagent.analyzer.result.OperationDep;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.dto.PrdAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.14(G24): 覆盖清单摘要化单测——旧实现 item.putAll(ep.toContextMap()) 把
 * requestBody/responseBody/businessLogic 等完整详情塞进 checklist，同一内容在
 * context.endpoints 已完整注入过一次（实测 159KB 纯冗余，432KB prompt 触发
 * 300k 保险丝）。新实现清单只留对账标识字段。
 * 消费方契约：remainingGaps/TestCaseReviewAgent 只读 id/method/path。
 */
class TestGeneratorAgentChecklistSummaryTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    @SuppressWarnings("unchecked")
    private Map<String, Object> checklist(BackendResult backend, PrdAnalysisResult prd) {
        Map<String, Object> coverage = agent.buildCoverageChecklist(prd, null, backend);
        return (Map<String, Object>) coverage.get("checklist");
    }

    @Test
    void endpointItemsContainOnlyIdentificationFields() {
        EndpointInfo ep = EndpointInfo.builder()
                .method("POST")
                .path("/api/order")
                .function("OrderController.create")
                .description("创建订单")
                .requestBody("{\"userId\":1}")
                .responseBody("{\"orderId\":9}")
                .businessLogic("校验库存后落库")
                .build();
        BackendResult backend = new BackendResult();
        backend.setEndpoints(List.of(ep));

        List<Map<String, Object>> items = (List<Map<String, Object>>) checklist(backend, null).get("endpoints");

        assertEquals(1, items.size());
        Map<String, Object> item = items.get(0);
        assertEquals("POST /api/order", item.get("id"));
        assertEquals("POST", item.get("method"));
        assertEquals("/api/order", item.get("path"));
        assertEquals("OrderController.create", item.get("function"));
        assertNull(item.get("requestBody"), "详情字段不得进覆盖清单（context.endpoints 已有全量）");
        assertNull(item.get("responseBody"));
        assertNull(item.get("businessLogic"));
        assertNull(item.get("description"));
    }

    @Test
    void ruleItemsTruncateLongRuleText() {
        // 100 字符规则文本——触发 80 字符截断
        BusinessRule br = BusinessRule.builder()
                .rule("订单金额超过一万元时必须经过风控审核，审核通过后方可进入支付流程，否则订单将被冻结并通知用户；"
                        + "同一用户三十分钟内最多发起五次支付请求，超出后需要等待冷却期结束方可重试，"
                        + "风控审核结果将在两个工作日内通过站内信通知用户本人。")
                .ruleType("validation")
                .build();
        BackendResult backend = new BackendResult();
        backend.setBusinessRules(List.of(br));

        List<Map<String, Object>> items = (List<Map<String, Object>>) checklist(backend, null).get("businessRules");

        assertEquals(1, items.size());
        Map<String, Object> item = items.get(0);
        assertEquals("rule-1", item.get("id"));
        assertEquals("validation", item.get("ruleType"));
        String rule = String.valueOf(item.get("rule"));
        assertTrue(rule.length() <= 83, "规则文本截断到 80 字符 + 省略号，实际 " + rule.length());
        assertTrue(rule.endsWith("..."));
        assertEquals(3, item.size(), "规则项只留 id/ruleType/rule 三个字段");
    }

    @Test
    void dependencyItemsContainOnlyId() {
        OperationDep od = OperationDep.builder()
                .operation("OrderService.createOrder")
                .kind("service")
                .description("创建订单核心服务")
                .dependsOn(List.of("StockService.deduct", "OrderMapper.insert"))
                .build();
        BackendResult backend = new BackendResult();
        backend.setDependencyGraph(List.of(od));

        List<Map<String, Object>> items = (List<Map<String, Object>>) checklist(backend, null).get("operationDependencies");

        assertEquals(1, items.size());
        Map<String, Object> item = items.get(0);
        assertEquals("OrderService.createOrder", item.get("id"));
        assertEquals(1, item.size(), "依赖项只留对账键 id");
        assertNull(item.get("dependsOn"));
    }

    @Test
    void requirementsKeepTitleAndDescriptionForGapMatching() {
        // 回归保护：G4 兜底匹配依赖 requirements 的 title/description——摘要化不得误伤
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("title", "登录功能");
        req.put("description", "用户可使用账号密码登录系统");
        PrdAnalysisResult prd = new PrdAnalysisResult();
        prd.setRequirements(List.of(req));

        List<Map<String, Object>> items = (List<Map<String, Object>>) checklist(null, prd).get("requirements");

        assertEquals(1, items.size());
        assertEquals("登录功能", items.get(0).get("title"));
        assertEquals("用户可使用账号密码登录系统", items.get(0).get("description"));
        assertTrue(String.valueOf(items.get(0).get("id")).startsWith("req-"));
        assertFalse(String.valueOf(items.get(0).get("id")).isEmpty());
    }
}
