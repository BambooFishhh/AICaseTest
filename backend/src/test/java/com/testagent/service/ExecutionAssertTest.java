package com.testagent.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.6(L6): 共享断言工具测试——三层断言（URL/标题 → DOM 文本 textSnippet → skipped）。
 * 业务背景：expected 此前从未被验证，"用例通过"≠"预期结果成立"；
 * v7.0(E4) 只比对 url/title，v7.6 扩展 DOM 文本断言（G20 的 UI 现象形 expected 具备可执行前提）。
 */
class ExecutionAssertTest {

    private Map<String, String> page(String url, String title, String snippet) {
        Map<String, String> m = new HashMap<>();
        m.put("url", url);
        m.put("title", title);
        if (snippet != null) {
            m.put("textSnippet", snippet);
        }
        return m;
    }

    @Test
    void urlKeywordMatchedPasses() {
        assertEquals("passed", ExecutionAssert.assertExpected(
                "URL 包含 /order/list", page("/order/list?page=1", "订单列表", null)));
    }

    @Test
    void urlKeywordMissedFails() {
        assertEquals("failed", ExecutionAssert.assertExpected(
                "URL 跳转 /login 页面", page("/home", "首页", null)));
    }

    @Test
    void chineseOnlyTargetWithoutSnippetIsSkipped() {
        // 无 textSnippet（页面状态无文本快照）且 url/title 无法机械比较 → 未验证
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "URL 跳转首页", page("/home", "首页", null)));
    }

    // ==================== 层 2: DOM 文本断言 ====================

    @Test
    void domTextKeywordHitPasses() {
        // toast/提示文案出现在页面 body 文本中 → 通过（v7.6 新能力：中文断言）
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面出现删除成功提示",
                page("/order/list", "订单列表", "订单列表 删除成功 操作已完成")));
    }

    @Test
    void domTextKeywordMissedFails() {
        // 期望"库存不足"提示，页面文本没有 → 明确失败（不再无条件假通过）
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面提示库存不足",
                page("/order/create", "创建订单", "创建订单 提交成功")));
    }

    @Test
    void chineseOnlyTargetWithSnippetIsVerifiable() {
        // "URL 跳转首页"——层 1 提取不到英文 token，落入层 2 中文断言
        assertEquals("passed", ExecutionAssert.assertExpected(
                "URL 跳转首页", page("/home", "首页", "首页 欢迎回来")));
        assertEquals("failed", ExecutionAssert.assertExpected(
                "URL 跳转首页", page("/home", "订单", "订单列表")));
    }

    @Test
    void multipleKeywordsAllMustHit() {
        // 多个关键词：全部命中才通过
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面出现提交成功且返回订单列表",
                page("/order/list", "订单列表", "提交成功 订单列表")));
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面出现提交成功且库存充足",
                page("/order/list", "订单列表", "提交成功")));
    }

    @Test
    void englishTokenInSnippetCaseInsensitive() {
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面出现 Order Created 提示",
                page("/orders", "订单", "ORDER CREATED at 2026-08-23")));
    }

    @Test
    void apiStyleExpectedIsSkippedNotFailed() {
        // API 形态断言无中文关键词且无英文 token 可提取 → 未验证，不误报失败
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "status=PENDING_PAYMENT", page("/api/order/1", "订单详情", "订单详情")));
    }

    @Test
    void emptySnippetIsSkipped() {
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "页面出现删除成功提示", page("/order/list", "订单列表", "")));
    }

    @Test
    void blankExpectedAndNullPageStateAreSkipped() {
        assertEquals("skipped", ExecutionAssert.assertExpected("", page("/home", "首页", null)));
        assertEquals("skipped", ExecutionAssert.assertExpected(null, page("/home", "首页", null)));
        assertEquals("skipped", ExecutionAssert.assertExpected("页面出现删除成功提示", null));
    }

    @Test
    void describeContainsExpectedAndActual() {
        String desc = ExecutionAssert.describe("页面提示库存不足",
                page("/order/create", "创建订单", "提交成功"));
        assertTrue(desc.contains("库存不足"), "描述应含期望文本");
        assertTrue(desc.contains("/order/create"), "描述应含实际 URL");
    }

    @Test
    void snippetSummaryTruncatedTo120Chars() {
        String longSnippet = "a".repeat(300);
        assertEquals(123, ExecutionAssert.snippetSummary(Map.of("textSnippet", longSnippet)).length());
        assertEquals("短文本", ExecutionAssert.snippetSummary(Map.of("textSnippet", "短文本")));
    }

    @Test
    void quotedPlaceholderMatchesActualDigits() {
        // v9.2: 占位符断言语义——'共 N 件收藏' 的 N 匹配页面实际数字
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示'共 N 件收藏'",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 共 1 件收藏 蔓越莓曲奇 200克 ￥36 删除")));
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示'共 N 件收藏'",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 共 12 件收藏 商品B ￥7 删除")));
    }

    @Test
    void quotedPlaceholderFailsWhenPatternAbsent() {
        // 页面上没有"共 <数字> 件收藏"形态 → failed（真实缺失）
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面显示'共 N 件收藏'",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 暂无收藏商品")));
    }

    @Test
    void quotedWithoutPlaceholderStaysStrict() {
        // 无占位符的引号断言保持字面严格匹配
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面显示'共 N 件收藏'",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 商品A")));
    }

    @Test
    void placeholderMetaClauseIsNotPageCopy() {
        // v9.3 实测回归：litemall 收藏页——"且N为实际商品列表数量"是对占位符 N 的语义限定，
        // 不是页面文案。此前"为实际商品列表数量"被抽成中文核心短语做 3-gram 匹配必然落空，
        // 引号短语明明命中仍误报 failed。
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示'共 N 件收藏'，且N为实际商品列表数量",
                page("http://172.31.160.1:6255/#/collect", "litemall 商城",
                        "我的收藏 共 1 件收藏 蔓越莓曲奇 200克 酥脆奶香，甜酸回味 ￥36 删除")));
        // 占位符正则仍是权威校验：页面没有"共 <数字> 件收藏"形态时照样 failed
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面显示'共 N 件收藏'，且N为实际商品列表数量",
                page("http://172.31.160.1:6255/#/collect", "litemall 商城",
                        "我的收藏 暂无收藏商品")));
    }

    @Test
    void bracePlaceholderVarNameNotRequiredInPageText() {
        // v9.3: {orderId} 的变量名此前会被抽成英文 token 要求页面出现 "orderId"——
        // 现占位符子句整体剔除，引号短语按"订单号 <数字>"正则匹配
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示'订单号 {orderId}'",
                page("http://host/#/order/1", "订单详情",
                        "订单详情 订单号 12345 下单时间 2026-08-31")));
    }

    @Test
    void clausesWithoutPlaceholderStillVerified() {
        // 无占位符的子句不受剔除影响，保持原有字面校验强度（缺失即 failed）
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面显示'共 1 件收藏'，页面显示'购物车为空'",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 共 1 件收藏 商品A")));
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示'共 1 件收藏'，页面显示'购物车为空'",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 共 1 件收藏 商品A 购物车为空")));
    }

    // ==================== v9.4: 负向断言 / 泛化限定语 / 混合型断言 ====================

    @Test
    void negativeQuotedAssertionPassesWhenAbsent() {
        // v9.4 实测回归：litemall 收藏页"不显示'已取消收藏'提示"——语义是该文案不应出现，
        // 旧实现按 must-contain 处理必然误判 failed；泛化子句（操作无响应/保持不变）同时剔除
        assertEquals("passed", ExecutionAssert.assertExpected(
                "操作无响应或页面无变化，收藏总数保持不变，不显示'已取消收藏'提示",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 共 1 件收藏 商品A 删除")));
        // 文案实际出现 → 负向断言失败（真实回归）
        assertEquals("failed", ExecutionAssert.assertExpected(
                "操作无响应或页面无变化，收藏总数保持不变，不显示'已取消收藏'提示",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 已取消收藏 共 0 件收藏")));
    }

    @Test
    void mixedVagueQualifierWithAnchorPassesOnAnchor() {
        // v9.4 实测回归：引号锚点命中即通过——"至少一个商品卡片"是泛化限定语，
        // 不再随 3-gram 匹配制造误判（同一断言此前"锚点明明命中仍 failed"）
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示'浏览足迹'，列表中包含至少一个商品卡片，例如'蔓越莓曲奇'",
                page("http://host/#/footprint", "litemall 商城",
                        "浏览足迹 共 7 条足迹 蔓越莓曲奇 200克 ￥36 删除")));
        // 锚点真实缺失照样 failed
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面显示'浏览足迹'，列表中包含至少一个商品卡片，例如'蔓越莓曲奇'",
                page("http://host/#/footprint", "litemall 商城",
                        "浏览足迹 共 0 条足迹 暂无足迹")));
    }

    @Test
    void noAnchorVagueDescriptiveIsSkipped() {
        // v9.4: 无引号锚点且全为泛化描述（如…等/详情信息/未发生变化）→ 无可验证内容，诚实 skipped
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "页面显示商品详情信息，如商品图片、规格选择等",
                page("http://host/#/goods/1", "litemall 商城", "蔓越莓曲奇 200克 ￥36")));
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "提示消失后，足迹列表内容与总数均未发生变化",
                page("http://host/#/footprint", "litemall 商城", "浏览足迹 共 7 条足迹")));
    }

    @Test
    void describeFlagsAppErrorWhenSystemErrorPresent() {
        // v12.17-C: 页面出现"系统内部错误"时标注 app_error——litemall 足迹接口 500 被
        // 混在断言失败里会误读为用例写得不好，需与被测应用侧问题区分
        String desc = ExecutionAssert.describe(
                "页面显示'暂无足迹'",
                page("http://host/#/footprint", "litemall 商城",
                        "浏览足迹 共 7 条足迹 暂无足迹 系统内部错误"));
        assertTrue(desc.contains("app_error"), "应含应用错误标注: " + desc);

        String normal = ExecutionAssert.describe(
                "页面显示'暂无足迹'",
                page("http://host/#/footprint", "litemall 商城", "浏览足迹 暂无足迹"));
        assertFalse(normal.contains("app_error"), "正常页面不应标注应用错误");
    }

    @Test
    void urlClauseQuoteMatchesAgainstUrlNotBody() {
        // v9.5fix 实测回归：litemall #4——"页面URL包含'/goods/'" 实际 URL 命中却 failed。
        // 根因：v9.4 子句模型把含引号的 URL 子句排除出层1 token 提取，引号路径被拿去
        // 匹配页面正文（正文快照不含 URL）必然误判；现 URL 语义子句的引号短语与 url/title 比对
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面URL包含'/goods/'，页面显示商品名称和详细信息",
                page("http://host/#/goods/1116011", "litemall 商城",
                        "蔓越莓曲奇 200克 ￥36 加入购物车")));
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面URL包含'/goods/'",
                page("http://host/#/user", "litemall 商城", "user123 欢迎回来")));
        // 非 URL 子句的引号短语仍与页面正文比对
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示'蔓越莓曲奇'，URL包含'/goods/'",
                page("http://host/#/goods/1116011", "litemall 商城",
                        "蔓越莓曲奇 200克 ￥36")));
    }

    @Test
    void whitespaceNewlineBetweenAnchorTokensStillMatches() {
        // v9.7 实测回归：用户中心页面文本按行拼接，'user123 欢迎回来' 与
        // '0 待付款 1 待发货' 的字面锚点因换行被拆开，必须归一空白后匹配
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示用户欢迎语（如'user123 欢迎回来'）",
                page("http://host/#/user", "litemall 商城",
                        "user123\n欢迎回来\n0\n待付款\n1\n待发货\n0\n待收货\n0\n待评价")));
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示订单统计（如'0 待付款 1 待发货'）",
                page("http://host/#/user", "litemall 商城",
                        "user123 欢迎回来 0 待付款 1 待发货 0 待收货")));
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面显示'user123 欢迎回来'",
                page("http://host/#/user", "litemall 商城",
                        "guest\n请先登录")));
    }

@Test
    void arithmeticPlaceholderMatchesAnyCount() {
        // v9.5fix: '共 N-1 件收藏' 的增减量无法从页面文本独立验证，按普通 N 处理（匹配任意数字）
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面顶部显示'共 N-1 件收藏'",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 共 1 件收藏 商品A")));
    }

    // ==================== v9.8: 泛化子句补全（litemall 足迹/收藏页实测回归） ====================

    @Test
    void genericListItemClauseDroppedWhenAnchorMatched() {
        // 实测回归：足迹页断言'出现包含商品名称的足迹商品列表项'是 UI 概念描述，
        // 页面文本不存在字面"列表项"，3-gram 必然落空误判 failed；引号锚点'共 N 条足迹'命中即通过
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示'共 N 条足迹'，出现包含商品名称的足迹商品列表项",
                page("http://host/#/footprint", "litemall 商城",
                        "浏览足迹 共 23 条足迹 全选 删除选中 轻奢纯棉刺绣水洗四件套 ￥899")));
        // 引号锚点真实缺失照样 failed
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面显示'共 N 条足迹'，出现包含商品名称的足迹商品列表项",
                page("http://host/#/footprint", "litemall 商城",
                        "浏览足迹 暂无足迹")));
    }

    @Test
    void comparativeCountClauseIsSkipped() {
        // 实测回归：'列表商品数量与点击前一致'是前后对比描述，页面文本无法独立验证，
        // 无引号锚点整句诚实降级 skipped（不再 3-gram 必然失败）
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "列表商品数量与点击前一致",
                page("http://host/#/footprint", "litemall 商城",
                        "浏览足迹 共 26 条足迹 全选 删除选中 轻奢纯棉刺绣水洗四件套")));
    }

    @Test
    void loginFormConceptClauseNotLiteralText() {
        // 实测回归：'显示登录表单'的"表单"是 UI 概念，页面文本只有"登录"按钮无字面"登录表单"；
        // 该子句剔除后 URL 引号锚点 '/login' 命中即通过
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面URL包含'/login'，显示登录表单",
                page("http://172.31.160.1:6255/#/login", "litemall 商城",
                        "litemall 欢迎来到商城 账号 密码 登录 测试账号：user123 / user123")));
        // URL 未命中仍失败（锚点真实缺失）
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面URL包含'/login'，显示登录表单",
                page("http://172.31.160.1:6255/#/collect", "litemall 商城",
                        "我的收藏 共 1 件收藏")));
    }

    @Test
    void checkboxStateClauseIsSkippedAsUnverifiable() {
        // 复选框"选中状态/被勾选"无法从页面文本快照验证——含'全选'引号锚点时按锚点通过，
        // 无引号锚点的纯状态描述诚实 skipped 而非 3-gram 误判失败
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示'全选'复选框为选中状态，列表复选框均被勾选",
                page("http://host/#/footprint", "litemall 商城",
                        "浏览足迹 共 8 条足迹 全选 删除选中 轻奢纯棉刺绣水洗四件套")));
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "列表复选框均被勾选，选中状态已保持",
                page("http://host/#/footprint", "litemall 商城",
                        "浏览足迹 共 8 条足迹 全选 删除选中 轻奢纯棉刺绣水洗四件套")));
    }

    // ==================== v9.8: 相对比较/状态泛化子句（最近批次 TC-917~938 实测回归） ====================

    @Test
    void arithmeticPlaceholderAnyLetterPlusMinusNormalized() {
        // 实测回归：'共 N+1 件收藏' 增减量无法独立验证——N+1 归一为 N 后按数字匹配；
        // '总数增加1' 是相对比较子句，剔除；锚点命中即通过
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示'共 N+1 件收藏'，总数增加1",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 共 1 件收藏 轻奢纯棉刺绣水洗四件套 ￥899")));
        // 任意单字母占位符 ± 任意数字同样归一（M-2 → M）
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示'共 M-2 件收藏'",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 共 3 件收藏 商品A")));
    }

    @Test
    void relativeComparisonClauseIsSkipped() {
        // 实测回归：'列表中包含刚刚收藏的商品卡片，且页面显示的收藏总数比操作前增加1'
        // 是相对比较描述，无引号锚点，整句诚实 skipped（页面单快照无法验证前后增量）
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "列表中包含刚刚收藏的商品卡片，且页面显示的收藏总数比操作前增加1",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 共 1 件收藏 轻奢纯棉刺绣水洗四件套 ￥899 删除")));
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "页面提示删除失败或无反应，列表和总数不变",
                page("http://host/#/footprint", "litemall 商城",
                        "浏览足迹 共 35 条足迹 暂无足迹")));
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "无新提示，页面状态不变",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 共 1 件收藏 轻奢纯棉刺绣水洗四件套 ￥899 删除")));
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "页面展示商品列表，非加载状态",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 共 1 件收藏 轻奢纯棉刺绣水洗四件套 ￥899 删除")));
    }

    @Test
    void iconStateClauseWithAnchorPassesOtherwiseSkipped() {
        // 实测回归：收藏按钮回显——含 '收藏' 引号锚点按锚点通过；
        // 无锚点的图标状态/初始样式描述（恢复初始样式）无法从文本验证，诚实 skipped
        assertEquals("passed", ExecutionAssert.assertExpected(
                "图标显示为未收藏状态，如'收藏'文字或图标样式不同",
                page("http://host/#/goods/1006007", "litemall 商城",
                        "轻奢纯棉刺绣水洗四件套 ￥899 收藏 加入购物车")));
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "图标显示为已收藏状态，恢复初始样式",
                page("http://host/#/goods/1006007", "litemall 商城",
                        "轻奢纯棉刺绣水洗四件套 ￥899 已收藏 加入购物车")));
    }

    @Test
    void goodsListConceptClauseDroppedWhenAnchorMatched() {
        // 实测回归：'列表展示商品卡片''页面显示足迹商品列表'是 UI 结构描述，页面文本无字面
        // 3-gram；引号锚点 '共 N 件收藏'/'删除选中' 命中即通过
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示'共 N 件收藏'（N为实际数量），列表展示商品卡片",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 共 1 件收藏 轻奢纯棉刺绣水洗四件套 ￥899 删除")));
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示足迹商品列表，包含'删除选中'按钮",
                page("http://host/#/footprint", "litemall 商城",
                        "浏览足迹 共 34 条足迹 全选 删除选中 轻奢纯棉刺绣水洗四件套 ￥899")));
        // 引号锚点真实缺失照样 failed
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面显示足迹商品列表，包含'删除选中'按钮",
                page("http://host/#/footprint", "litemall 商城",
                        "浏览足迹 共 0 条足迹 暂无足迹")));
    }

    @Test
    void genericTotalCountClauseIsSkipped() {
        // 实测回归：'页面显示足迹总数''页面显示商品收藏的总数'是无引号的泛化描述，
        // 页面文本无字面"总数"文案，诚实 skipped 而非 3-gram 误判失败
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "页面显示足迹总数",
                page("http://host/#/footprint", "litemall 商城",
                        "浏览足迹 共 36 条足迹 全选 删除选中 轻奢纯棉刺绣水洗四件套")));
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "页面显示商品收藏的总数",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 共 1 件收藏 轻奢纯棉刺绣水洗四件套 ￥899 删除")));
        // 带引号锚点仍按锚点验证
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示'共 1 件收藏'总数",
                page("http://host/#/collect", "litemall 商城",
                        "我的收藏 共 1 件收藏 轻奢纯棉刺绣水洗四件套 ￥899 删除")));
    }

    @Test
    void listPresenceFallbackVerifiesViaCount() {
        // v9.11 实测回归：通过用例的最后一步断言"展示至少一个商品卡片"被泛化词表诚实
        // skipped——但这类断言有通用验证途径：页面总数文案。N≥1 即列表有项 → passed
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面展示至少一个收藏的商品卡片，包含商品图片、名称和价格",
                page("http://host/#/collect", "litemall 商城", "我的收藏 共 1 件收藏 蔓越莓曲奇")));
        // 总数为 0 → "至少一项"断言失败（列表真实为空）
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面展示至少一个收藏的商品卡片，包含商品图片、名称和价格",
                page("http://host/#/collect", "litemall 商城", "我的收藏 共 0 件收藏 暂无收藏")));
    }

    @Test
    void emptyStateFallbackVerifiesViaCount() {
        // v9.11: 空状态断言反向兜底——总数为 0 即通过，有项即失败
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面显示暂无足迹的空状态组件",
                page("http://host/#/footprint", "litemall 商城", "浏览足迹 共 0 条足迹")));
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面显示暂无足迹的空状态组件",
                page("http://host/#/footprint", "litemall 商城", "浏览足迹 共 6 条足迹 商品A")));
        // 页面无"共 N"总数文案 → 无法兜底验证，维持诚实 skipped
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "页面显示暂无足迹的空状态组件",
                page("http://host/#/footprint", "litemall 商城", "浏览足迹")));
    }
}
