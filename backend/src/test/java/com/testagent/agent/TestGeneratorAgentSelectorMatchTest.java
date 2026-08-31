package com.testagent.agent;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * v12.16-A: text 选择器快速通道——action+target 完整包含按钮可见文本且唯一命中时直接采用。
 * 短中文文本（"删除"）在原 token 打分制下低于 3 分阈值必被拒，纯文本按钮将失去唯一
 * 的确定性定位途径；多按钮含同文本属歧义，宁空不赌。
 */
class TestGeneratorAgentSelectorMatchTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    private Map<String, Object> selector(String type, String value) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("value", value);
        m.put("element", "button");
        m.put("label", value);
        return m;
    }

    @Test
    void textSelectorUniqueContainmentWins() {
        List<Map<String, Object>> pool = List.of(
                selector("text", "删除"),
                selector("text", "全选"),
                selector("css", ".footprint-list"));
        Map<String, Object> best = agent.bestSelector(
                pool, "找到第一条足迹记录，点击其右侧的删除按钮");
        assertEquals("text", best.get("type"));
        assertEquals("删除", best.get("value"));
    }

    @Test
    void overlappingTextStillUniqueContainmentWins() {
        // "删除选中"不是 target 子串——唯一包含命中的"删除"直接采用
        List<Map<String, Object>> pool = List.of(
                selector("text", "删除"),
                selector("text", "删除选中"));
        Map<String, Object> best = agent.bestSelector(pool, "点击删除按钮");
        assertEquals("text", best.get("type"));
        assertEquals("删除", best.get("value"));
    }

    @Test
    void duplicateTextButtonsAreAmbiguous() {
        // 列表每行都有"删除"按钮——同名按钮多个 = 歧义，宁空不赌（留给视觉兜底）
        List<Map<String, Object>> pool = List.of(
                selector("text", "删除"),
                selector("text", "删除"));
        assertNull(agent.bestSelector(pool, "点击删除按钮"));
    }

    @Test
    void nonContainedTextDoesNotHit() {
        List<Map<String, Object>> pool = List.of(selector("text", "清空足迹"));
        // target 是"删除按钮"，不含"清空足迹" → 快速通道不命中
        assertNull(agent.bestSelector(pool, "点击删除按钮"));
    }
}
