package com.testagent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v12.30: 列表内容断言"字段名:具体业务名"去钉化——运行时数据值（商品名/昵称）被生成侧写死成
 * 引号锚点，跨账号漂移必失败（实测 TC-1193 step9：列表正常显示 共N件，仅因 user123 收藏的不是
 * '蔓越莓曲奇' 而 failed）。demotePinnedInText 只剥"名称/昵称等字段词 + 引号值"结构，
 * 不触碰固定 UI 文案（登录提示/按钮/toast/'共 N 件收藏'/URL 等）。
 */
class TestGeneratorAgentPinnedNameTest {

    // ---- 列表展示点名商品名：剥名 + 追加存在性触发 LIST_PRESENCE ----
    @Test
    void pinnedProductInListGetsDemotedToExistence() {
        String out = TestGeneratorAgent.demotePinnedInText(
                "列表中显示商品卡片，包含商品名称'蔓越莓曲奇'和价格");
        assertFalse(out.contains("蔓越莓曲奇"), "商品名应被剥离: " + out);
        assertTrue(out.contains("至少一个"), "应追加存在性断言触发共N件兜底: " + out);
    }

    // ---- URL 已存在时仅剥离商品名，保留 URL 锚点供 URL 断言 ----
    @Test
    void pinnedProductBesideUrlKeepsUrlAnchor() {
        String out = TestGeneratorAgent.demotePinnedInText(
                "页面URL包含'/goods/'，页面显示商品名称'蔓越莓曲奇'");
        assertFalse(out.contains("蔓越莓曲奇"), "商品名应被剥离: " + out);
        assertTrue(out.contains("/goods/"), "URL 锚点应保留: " + out);
    }

    // ---- 固定 UI 文案（无"名称"字段词前导）一律原样保留 ----
    @Test
    void fixedUiTextUntouched() {
        assertEquals("页面显示'用户名或密码错误'提示",
                TestGeneratorAgent.demotePinnedInText("页面显示'用户名或密码错误'提示"));
        assertEquals("页面显示'共 N 件收藏'",
                TestGeneratorAgent.demotePinnedInText("页面显示'共 N 件收藏'"));
        assertEquals("页面提示'已收藏'",
                TestGeneratorAgent.demotePinnedInText("页面提示'已收藏'"));
    }

    // ---- 名称带冒号/为的变体也剥离 ----
    @Test
    void variantsStripName() {
        assertFalse(TestGeneratorAgent.demotePinnedInText("页面显示商品名称为'轻奢四件套'")
                .contains("轻奢四件套"));
        assertFalse(TestGeneratorAgent.demotePinnedInText("列表包含名称为'曲奇'的商品")
                .contains("曲奇"));
    }

    // ---- v13.10: 倒置形态"包含'蔓越莓曲奇'等商品名"（实测 TC-1310）也剥离 ----
    @Test
    void reversedPinnedProductGetsDemoted() {
        String out = TestGeneratorAgent.demotePinnedInText(
                "页面显示商品卡片，包含'蔓越莓曲奇'等商品名，且每个卡片有图片区域、商品名、价格");
        assertFalse(out.contains("蔓越莓曲奇"), "倒置形态的商品名应被剥离: " + out);
        assertTrue(out.contains("商品名"), "应保留字段名字样: " + out);
        assertFalse(out.contains("'"), "引号锚点应清除: " + out);
        assertTrue(out.contains("至少一个"), "列表语境应追加存在性断言: " + out);
    }

    // ---- 昵称类字段值剥离 ----
    @Test
    void nicknameStrip() {
        assertFalse(TestGeneratorAgent.demotePinnedInText("页面右上角显示昵称'user123'")
                .contains("user123"));
    }

    // ---- 无点名时原样返回 ----
    @Test
    void noPinUntouched() {
        String exp = "页面展示至少一个收藏的商品卡片，包含名称、价格";
        assertEquals(exp, TestGeneratorAgent.demotePinnedInText(exp));
    }
}
