package com.testagent.agent;

import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v9.9: 落库前按模块归组排序——并发生成到达序会把平台 id 与模块块打散（实测 TC-1004
 * 插在 TC-998/TC-999 之间），project_seq 按模块重排后与 id 视觉错位显"乱序"。
 * 排序后 id 分配顺序 = 模块块顺序，id 序/序号/分组显示三者单调一致。
 */
class TestGeneratorAgentModuleOrderTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    private TestCase tc(String module) {
        TestCase t = new TestCase();
        t.setTitle("t-" + module);
        t.setModule(module);
        return t;
    }

    @Test
    void casesSortedByModuleFirstAppearanceKeepsInnerOrder() {
        // 并发到达序：收藏/足迹/收藏/足迹——排序后按模块首次出现归组，组内保持到达序
        List<TestCase> cases = new ArrayList<>(List.of(
                tc("我的收藏"), tc("浏览足迹管理"), tc("我的收藏"), tc("浏览足迹管理")));
        agent.sortCasesByModule(cases);

        assertEquals("我的收藏", cases.get(0).getModule());
        assertEquals("我的收藏", cases.get(1).getModule());
        assertEquals("浏览足迹管理", cases.get(2).getModule());
        assertEquals("浏览足迹管理", cases.get(3).getModule());
    }

    @Test
    void interleavedIdsRegroupIntoContiguousBlocks() {
        // 实测场景：TC-1004（模块 B）落在 TC-998 与 TC-999 之间——排序后模块块连续
        TestCase a1 = tc("我的收藏"); a1.setId("TC-991");
        TestCase b1 = tc("浏览足迹管理"); b1.setId("TC-998");
        TestCase b2 = tc("浏览足迹管理"); b2.setId("TC-1004");
        TestCase a2 = tc("我的收藏"); a2.setId("TC-992");
        List<TestCase> cases = new ArrayList<>(List.of(a1, b1, b2, a2));
        agent.sortCasesByModule(cases);

        assertEquals("TC-991", cases.get(0).getId());
        assertEquals("TC-992", cases.get(1).getId());
        assertEquals("TC-998", cases.get(2).getId());
        assertEquals("TC-1004", cases.get(3).getId());
    }

    @Test
    void nullOrBlankModuleGroupsAsUncategorized() {
        List<TestCase> cases = new ArrayList<>(List.of(
                tc(null), tc("我的收藏"), tc("  ")));
        agent.sortCasesByModule(cases);

        // null 与空白归入同一"未分类"组，首次出现在最前
        assertEquals("我的收藏", cases.get(2).getModule());
        assertEquals("t-" + "我的收藏", cases.get(2).getTitle());
    }
}
