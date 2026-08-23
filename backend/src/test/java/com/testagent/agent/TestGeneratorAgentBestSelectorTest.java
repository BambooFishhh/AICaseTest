package com.testagent.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * v7.10(L12): 选择器匹配阈值收紧单测——旧实现 score>=2 即匹配且并列最高取先遍历者，
 * "删除"会匹配到"批量删除"按钮，错误被固化进用例资产。
 * 新规则：阈值 3 且要求唯一最高分（并列宁留空，由 Agent 模式执行时 LLM 自定位）。
 */
class TestGeneratorAgentBestSelectorTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    private Map<String, Object> selector(String value) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("value", value);
        s.put("element", "button");
        return s;
    }

    @Test
    void twoCharTokenMatchIsBelowThreshold() {
        // 旧实现："删除"（2 字 token，score=2 >= 2）即匹配"批量删除"按钮
        // 真实调用形态：text = action + " " + target（空格分隔出独立中文 token）
        List<Map<String, Object>> pool = List.of(selector("批量删除"), selector("新增"));
        assertNull(agent.bestSelector(pool, "点击 删除 按钮"),
                "2 字 token 命中 score=2 低于新阈值 3，不应匹配");
    }

    @Test
    void uniqueHighScoreMatchIsReturned() {
        List<Map<String, Object>> pool = List.of(selector("批量删除"), selector("确认删除"));
        Map<String, Object> best = agent.bestSelector(pool, "点击 确认删除");
        assertNotNull(best, "唯一最高分（确认删除 4 字 token 命中）应返回");
        assertEquals("确认删除", best.get("value"));
    }

    @Test
    void tiedHighestScoreReturnsNull() {
        // 两个候选命中相同分数（都含"删除订单"4 字 token）→ 并列最高宁留空
        List<Map<String, Object>> pool = List.of(selector("删除订单按钮"), selector("删除订单链接"));
        assertNull(agent.bestSelector(pool, "点击 删除订单"),
                "并列最高分应留空（不赌先遍历者）");
    }

    @Test
    void noTokenOverlapReturnsNull() {
        List<Map<String, Object>> pool = List.of(selector("提交表单"), selector("重置密码"));
        assertNull(agent.bestSelector(pool, "点击 上传附件"), "无 token 交集不应匹配");
    }
}
