package com.testagent.agent;

import com.testagent.analyzer.result.BusinessRule;
import com.testagent.analyzer.result.EndpointInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.14(G25): context.endpoints/businessRules 容量控制单测——G17 弱过滤
 * （>0 即过）全放行后无总量控制，大项目 220 接口全量详情 128KB 灌 prompt。
 * 超上限按相关性降序保留 top-N（稳定排序，同分保持原序）；关键词空白保序截断。
 */
class TestGeneratorAgentContextCapTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    // v8.4: 生产默认已放宽到 160/150，本类验证截断机制本身，显式钉住小值避免断言随默认值漂移
    {
        ReflectionTestUtils.setField(agent, "endpointsContextMax", 80);
        ReflectionTestUtils.setField(agent, "rulesContextMax", 100);
    }

    private EndpointInfo ep(String path, String description) {
        return EndpointInfo.builder()
                .method("GET")
                .path(path)
                .function("Ctrl." + path.replace("/api/", ""))
                .description(description)
                .build();
    }

    @Test
    void oversizedListCappedToLimitWithHighRelevanceFirst() {
        // 220 个接口：1 个与关键词强相关 + 219 个无关——高分者必须入选
        List<EndpointInfo> eps = new ArrayList<>();
        eps.add(ep("/api/zzz-irrelevant-1", "无关接口"));
        for (int i = 2; i <= 220; i++) {
            eps.add(ep("/api/zzz-irrelevant-" + i, "无关接口"));
        }
        eps.add(ep("/api/order/create", "创建订单，订单金额校验"));

        List<EndpointInfo> capped = agent.capEndpointsByRelevance(eps, "订单 创建订单 金额");

        assertEquals(80, capped.size(), "超上限保留 top-80");
        assertTrue(capped.stream().anyMatch(e -> "/api/order/create".equals(e.getPath())),
                "与需求关键词高相关的接口必须入选");
    }

    @Test
    void underLimitReturnsSameInstance() {
        List<EndpointInfo> eps = List.of(ep("/api/a", "a"), ep("/api/b", "b"));

        assertSame(eps, agent.capEndpointsByRelevance(eps, "任意关键词"));
        assertSame(eps, agent.capEndpointsByRelevance(eps, ""));
    }

    @Test
    void blankKeywordKeepsOriginalOrder() {
        // 关键词空白（需求缺失场景）→ 不排序，按原序截断（确定性）
        List<EndpointInfo> eps = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            eps.add(ep("/api/item-" + String.format("%03d", i), "接口" + i));
        }

        List<EndpointInfo> capped = agent.capEndpointsByRelevance(eps, "  ");

        assertEquals(80, capped.size());
        assertEquals("/api/item-001", capped.get(0).getPath());
        assertEquals("/api/item-080", capped.get(79).getPath());
    }

    @Test
    void stableSortKeepsOriginalOrderAmongTies() {
        // 同分项保持原序——两次调用结果一致（确定性）
        List<EndpointInfo> eps = new ArrayList<>();
        for (int i = 1; i <= 90; i++) {
            eps.add(ep("/api/same-" + i, "同类接口"));
        }

        List<EndpointInfo> first = agent.capEndpointsByRelevance(eps, "同类");
        List<EndpointInfo> second = agent.capEndpointsByRelevance(eps, "同类");

        assertEquals(80, first.size());
        assertEquals(first, second, "同分稳定排序，两次截断结果一致");
        assertEquals("/api/same-1", first.get(0).getPath(), "同分保持原序——首个仍是原列表第一项");
    }

    @Test
    void rulesCappedToLimitWithRelevance() {
        List<BusinessRule> rules = new ArrayList<>();
        for (int i = 1; i <= 150; i++) {
            rules.add(BusinessRule.builder()
                    .rule((i == 150 ? "订单金额上限校验" : "无关规则" + i))
                    .ruleType("validation")
                    .build());
        }

        List<BusinessRule> capped = agent.capRulesByRelevance(rules, "订单 金额 校验");

        assertEquals(100, capped.size(), "规则超上限保留 top-100");
        assertTrue(capped.stream().anyMatch(r -> "订单金额上限校验".equals(r.getRule())),
                "高相关规则必须入选");
    }
}
