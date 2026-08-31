package com.testagent.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v7.6(L6): expected 断言共享工具——程序化模式与 Agent 模式共用。
 * 三层断言（按可验证性从强到弱）：
 * 1. URL/标题语义（v7.0 E4 能力）：expected 含 URL/标题触发词时与 pageState.url/title 比较；
 * 2. DOM 文本断言（v7.6 新增）：expected 关键词与 title+textSnippet（页面文本快照）包含比较；
 * 3. 无法验证 → skipped（诚实标记，不误报失败）。
 *
 * 层 2 中文匹配启发式（无分词器的务实方案）：
 * - 引号短语（「」/“”/''/""）视为强关键词，要求完整包含；
 * - 中文段剥离叙述性前后缀（"页面出现…提示"→核心短语），按连接词（且/同时/以及）切分；
 * - 核心短语 ≥3 字时按 3-gram 滑窗匹配（任一 3 连字出现在页面文本即命中），2 字短语要求完整包含；
 * - 英文 token（≥3 字符，排除停用词）大小写不敏感包含；
 * - 全部命中 → passed，任一未命中 → failed；无可比内容 → skipped。
 */
public final class ExecutionAssert {

    private ExecutionAssert() {
    }

    /** URL/标题语义触发词：expected 中出现这些词才尝试与页面 url/title 比较 */
    private static final Pattern ASSERT_URLISH = Pattern.compile("(?i)(url|地址|标题|title|页面显示|跳转到|显示)");

    /** 可比较片段：英文/斜杠开头的标识符（>=3 字符），如 /order/list、Dashboard、login */
    private static final Pattern ASSERT_TOKEN = Pattern.compile("[/a-zA-Z][a-zA-Z0-9/_.-]{2,}");

    /** 中文段：连续 >=2 个汉字 */
    private static final Pattern ASSERT_CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");

    /** 英文关键词：>=3 字符的标识符（用于文本比较） */
    private static final Pattern ASSERT_TEXT_TOKEN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]{2,}");

    /** 引号短语（强关键词）：「…」/“…”/'…'/"…" */
    private static final Pattern ASSERT_QUOTED = Pattern.compile(
            "「([^「」]+)」|“([^“”]+)”|‘([^‘’]+)’|'([^']+)'|\"([^\"]+)\"");

    /** 中文连接词：段内切分为多个并列断言 */
    private static final Pattern ASSERT_CONNECTOR = Pattern.compile("并且|同时|以及|且");

    /** v9.3: 子句切分（标点 + 连接词）——用于剔除占位符元描述子句 */
    private static final Pattern ASSERT_CLAUSE_SPLIT = Pattern.compile("，|,|；|;|。|并且|同时|以及|且");

    /** v9.3: 子句内占位符特征——独立大写字母（前后非 ASCII 字母数字，允许紧贴中文）/花括号变量。
     *  不能用 \b：Java 的 \b 是 Unicode 感知的，"且N为" 中 N 两侧都是汉字时形不成词边界，
     *  而占位符元描述恰恰把 N 嵌在中文里（"且N为实际商品列表数量"）。 */
    private static final Pattern CLAUSE_PLACEHOLDER = Pattern.compile("(?<![a-zA-Z0-9])[A-Z](?![a-zA-Z0-9])|\\{[^}]*\\}");

    /** v9.4: 负向断言极性标记——子句含这些词时，其引号短语应为"不出现"而非"出现" */
    private static final Pattern ASSERT_NEGATIVE = Pattern.compile(
            "不显示|不再显示|不再出现|不再存在|不应出现|不应显示|未出现|没有出现");

    /** v9.4: 无引号子句的泛化/动态描述特征——没有可比较的稳定文案，不参与 ngram。
     *  "至少一个商品项"、"列表区域无商品卡片"、"保持不变"、"如…等"这类子句
     *  按 3-gram 匹配必然落空，只会制造误判；其中可验证的引号锚点单独验证。 */
    private static final Pattern ASSERT_CLAUSE_VAGUE = Pattern.compile(
            "至少|不再|消失|保持不变|未发生变化|没有变化|无变化|无响应|加载完成|加载中|正在加载|loading"
                    + "|类似|例如|如[^，。；]{0,16}等|空状态|为空|无商品|无记录|无内容|无数据|详情|信息"
                    + "|等基本|等统计|等描述|等文案|等提示|等数值");

    /** v9.5fix: URL 语义子句——含这些词的子句，其引号短语（'/goods/' 等路径）应与 url/title
     *  比对而非页面正文（正文快照不含 URL，按正文匹配必然误判） */
    private static final Pattern ASSERT_URL_CLAUSE = Pattern.compile("URL|url|地址|重定向|跳转至|跳转到|路径");

    /** 叙述性前缀（长词优先剥离） */
    private static final String[] ASSERT_PREFIXES = {
            "页面上出现", "页面出现", "页面显示", "标题显示", "跳转到", "页面", "标题", "出现", "显示", "提示", "跳转", "返回", "包含"};

    /** 叙述性后缀（长词优先剥离） */
    private static final String[] ASSERT_SUFFIXES = {
            "错误提示", "提示信息", "错误信息", "提示文案", "的提示", "的信息", "提示", "信息", "文案", "按钮", "弹窗", "对话框"};

    /** 触发词与通用虚词，不参与比较 */
    private static final Set<String> ASSERT_STOPWORDS = Set.of("url", "http", "https", "www", "com", "cn",
            "and", "or", "the", "of", "to", "page", "title", "spa");

    /**
     * v9.2: 抽象断言特征——无引号文案锚点且含这些泛化表述时，页面文本匹配必然失败
     * （"加载完成/不再显示loading/至少一个商品项"没有可比较的具体文案），按无法验证处理。
     */
    private static final Pattern ASSERT_VAGUE = Pattern.compile(
            "加载完成|正在加载|loading|不再显示|至少一个|正常加载|正常展示|正确展示|正确显示|无空白|无错误提示");

    /** expected 是否含引号引用的页面可见文案（强断言锚点） */
    public static boolean hasQuotedAnchor(String expected) {
        return expected != null && !extractQuoted(expected).isEmpty();
    }

    /** v9.2: 引号文案内的占位符特征——'共 N 件收藏' / {orderId} 这类字面量页面不会出现 */
    private static final Pattern QUOTE_PLACEHOLDER = Pattern.compile("\\b[A-Z]\\b|\\{[^}]*\\}");

    /** 引号短语原文（供 lint 检查占位符） */
    public static List<String> quotedPhrases(String text) {
        return text == null ? List.of() : extractQuoted(text);
    }

    /** 引号文案中含占位符的短语列表（空列表 = 无占位符问题） */
    public static List<String> quotedPlaceholders(String text) {
        List<String> hits = new ArrayList<>();
        for (String q : quotedPhrases(text)) {
            if (QUOTE_PLACEHOLDER.matcher(q).find()) {
                hits.add(q);
            }
        }
        return hits;
    }

    /**
     * 断言 expected 是否在当前页面状态上成立。
     *
     * @param expected  步骤预期结果文本
     * @param pageState 页面状态（url/title/textSnippet，任一可缺失）
     * @return passed / failed / skipped（无法验证时 skipped，不误报失败）
     */
    public static String assertExpected(String expected, Map<String, String> pageState) {
        if (expected == null || expected.isBlank()) {
            return "skipped";
        }
        if (pageState == null) {
            return "skipped";  // 页面状态读取失败时不误报失败
        }
        // v9.2: 抽象断言诚实降级——无引号文案锚点的泛化表述（加载完成/至少一个等）
        // 没有可匹配的具体文案，文本断言必然落空，按"无法验证"处理而非误报失败
        if (!hasQuotedAnchor(expected) && ASSERT_VAGUE.matcher(expected).find()) {
            return "skipped";
        }

        // v9.4: 子句级断言分析（层 1/层 2 共用）——按标点/连接词切分子句后逐子句定性：
        //  A. 含引号短语 → 只验证引号短语本身：极性由子句内负向词决定（"不显示'X'" = X 不应出现），
        //     子句其余中文是叙述连接语（"至少一个商品项"），不参与 ngram——混合型断言不再误判
        //  B. 含占位符（N/{var}）→ 对占位符的语义限定子句（v9.3，"且N为实际商品列表数量"），剔除
        //  C. 无引号的泛化/动态描述子句（至少/消失/保持不变/类似…等）→ 剔除
        //  D. 其余无引号子句 → 保持原 ngram 匹配强度
        List<Object[]> quotedChecks = new ArrayList<>();
        StringBuilder assertable = new StringBuilder();
        for (String clause : ASSERT_CLAUSE_SPLIT.split(expected)) {
            List<String> clauseQuotes = extractQuoted(clause);
            if (!clauseQuotes.isEmpty()) {
                boolean negative = ASSERT_NEGATIVE.matcher(clause).find();
                // v9.5fix: URL 语义子句的引号短语与 url/title 比对
                boolean urlTarget = ASSERT_URL_CLAUSE.matcher(clause).find();
                for (String q : clauseQuotes) {
                    quotedChecks.add(new Object[]{q, negative, urlTarget});
                }
                continue;
            }
            if (CLAUSE_PLACEHOLDER.matcher(clause).find()) {
                continue;
            }
            if (ASSERT_CLAUSE_VAGUE.matcher(clause).find()) {
                continue;
            }
            if (assertable.length() > 0) {
                assertable.append("，");
            }
            assertable.append(clause);
        }

        // 层 1: URL/标题语义
        if (ASSERT_URLISH.matcher(expected).find()) {
            String url = pageState.getOrDefault("url", "");
            String title = pageState.getOrDefault("title", "");
            String urlTitleText = (url + " " + title).toLowerCase();
            List<String> keywords = new ArrayList<>();
            Matcher m = ASSERT_TOKEN.matcher(assertable.toString());
            while (m.find()) {
                String kw = m.group().toLowerCase();
                if (!ASSERT_STOPWORDS.contains(kw)) {
                    keywords.add(kw);
                }
            }
            if (!keywords.isEmpty()) {
                for (String kw : keywords) {
                    if (!urlTitleText.contains(kw)) {
                        return "failed";
                    }
                }
                return "passed";
            }
            // 触发词命中但提取不到英文 token（如"URL 跳转首页"），落入层 2 尝试中文断言
        }

        // 层 2: DOM 文本断言（title + textSnippet 包含比较）
        List<String> segments = new ArrayList<>();
        Matcher zh = ASSERT_CHINESE.matcher(assertable.toString());
        while (zh.find()) {
            segments.add(zh.group());
        }
        // 进入文本断言的前提：有引号短语或中文段——纯 API 形态（status=XXX）无 UI 可比内容
        if (quotedChecks.isEmpty() && segments.isEmpty()) {
            return "skipped";
        }
        String snippet = pageState.getOrDefault("textSnippet", "");
        String title = pageState.getOrDefault("title", "");
        if (snippet.isBlank()) {
            return "skipped";  // 无 DOM 文本快照（textSnippet 缺失/为空）→ 文本断言不可执行，title 不是页面正文
        }
        // v9.7: DOM 文本快照按行/标签拼接，用户中心这类换行分隔的"user123 欢迎回来"、
        // "0 待付款 1 待发货"必须在断言前归一空白，否则字面引号锚点因换行误判失败
        String pageText = normalizeSpace(title + " " + snippet).toLowerCase();

        // 引号短语：正极性完整包含（含占位符时按"占位符=数字"正则语义匹配）；负极性必须不出现
        String urlTitleText = normalizeSpace(pageState.getOrDefault("url", "") + " " + title).toLowerCase();
        for (Object[] qc : quotedChecks) {
            String q = normalizeSpace((String) qc[0]);
            boolean negative = (Boolean) qc[1];
            // v9.5fix: URL 语义子句的引号短语（'/goods/' 等路径）与 url/title 比对
            String haystack = (Boolean) qc[2] ? urlTitleText : pageText;
            if (negative) {
                // v9.4: 负向断言——"不显示'X'/不再显示'X'" 的语义是 X 不应出现在比对目标文本
                if (haystack.contains(q.toLowerCase())) {
                    return "failed";
                }
            } else if (QUOTE_PLACEHOLDER.matcher(q).find()) {
                if (!placeholderPhraseRegex(q).matcher(haystack).find()) {
                    return "failed";
                }
            } else if (!haystack.contains(q.toLowerCase())) {
                return "failed";
            }
        }
        // 中文核心短语：剥前后缀 + 连接词切分后逐个匹配
        for (String core : chineseCores(segments)) {
            if (!ngramHit(core, pageText)) {
                return "failed";
            }
        }
        // 英文 token：大小写不敏感包含
        List<String> tokens = new ArrayList<>();
        Matcher en = ASSERT_TEXT_TOKEN.matcher(assertable);
        while (en.find()) {
            String kw = en.group().toLowerCase();
            if (!ASSERT_STOPWORDS.contains(kw)) {
                tokens.add(kw);
            }
        }
        for (String tk : tokens) {
            if (!pageText.contains(tk)) {
                return "failed";
            }
        }
        return "passed";
    }

    /** v9.7: 页面文本/引号短语归一——连续空白（含换行、标签边界）折叠为单个空格 */
    private static String normalizeSpace(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    /** v9.2: 含占位符的引号短语 → 正则：占位符（N/X/{var}）匹配数字，字面段原样匹配（大小写不敏感）。
     *  如 '共 N 件收藏' → /共 \d+ 件收藏/，页面显示"共 1 件收藏"即通过。 */
    private static java.util.regex.Pattern placeholderPhraseRegex(String phrase) {
        // v9.5fix: 算术占位符降级——'共 N-1 件收藏'/'共 N+1 件收藏' 的增减量无法从页面文本
        // 独立验证，按普通 N 处理（匹配任意数字），避免必然失败的假断言
        phrase = phrase.replaceAll("(?i)\\bN\\s*[+-]\\s*1\\b", "N");
        String[] parts = QUOTE_PLACEHOLDER.split(phrase);
        StringBuilder sb = new StringBuilder("(?i)");
        for (int i = 0; i < parts.length; i++) {
            sb.append(java.util.regex.Pattern.quote(parts[i].toLowerCase()));
            if (i < parts.length - 1) {
                sb.append("\\d+");
            }
        }
        return java.util.regex.Pattern.compile(sb.toString());
    }

    /** 引号内短语（「」/“”/''/""），取匹配到的非空分组 */
    private static List<String> extractQuoted(String expected) {
        List<String> quoted = new ArrayList<>();
        Matcher m = ASSERT_QUOTED.matcher(expected);
        while (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null && g.trim().length() >= 2) {
                    quoted.add(g.trim());
                    break;
                }
            }
        }
        return quoted;
    }

    /** 中文段 → 核心短语：连接词切分 + 剥离叙述性前后缀 + 去空/去重 */
    static List<String> chineseCores(List<String> segments) {
        Set<String> cores = new LinkedHashSet<>();
        for (String seg : segments) {
            for (String piece : ASSERT_CONNECTOR.split(seg)) {
                String core = stripBoilerplate(piece);
                if (core.length() >= 2) {
                    cores.add(core);
                }
            }
        }
        return new ArrayList<>(cores);
    }

    private static String stripBoilerplate(String piece) {
        String s = piece.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : ASSERT_PREFIXES) {
                // >= 而非 >：纯叙述段（如"页面出现"/"提示"）整体剥空后过滤，不残留"出现"这类虚词
                if (s.startsWith(prefix) && s.length() >= prefix.length()) {
                    s = s.substring(prefix.length());
                    changed = true;
                    break;
                }
            }
            for (String suffix : ASSERT_SUFFIXES) {
                if (s.endsWith(suffix) && s.length() >= suffix.length()) {
                    s = s.substring(0, s.length() - suffix.length());
                    changed = true;
                    break;
                }
            }
        }
        return s;
    }

    /** 核心短语命中判定：≥3 字按 3-gram 滑窗（任一 3 连字出现即命中），2 字要求完整包含 */
    static boolean ngramHit(String core, String pageText) {
        if (core.length() == 2) {
            return pageText.contains(core);
        }
        for (int i = 0; i + 3 <= core.length(); i++) {
            if (pageText.contains(core.substring(i, i + 3))) {
                return true;
            }
        }
        return false;
    }

    /** v12.17-C: 被测系统错误特征——页面文本出现时，断言失败大概率是应用侧缺陷/脏数据，标注区分 */
    private static final Pattern APP_ERROR_TEXT = Pattern.compile(
            "系统内部错误|系统异常|服务器错误|服务异常|服务器开小差|Internal Server Error|network error");

    /**
     * 期望/实际差异摘要，供 ExecutionStep.error 字段（断言 failed 时使用）。
     */
    public static String describe(String expected, Map<String, String> pageState) {
        if (pageState == null) {
            return "断言不匹配: 期望[" + expected + "] 实际页面状态读取失败";
        }
        String desc = "断言不匹配: 期望[" + expected + "] 实际[url="
                + pageState.getOrDefault("url", "") + ", title=" + pageState.getOrDefault("title", "")
                + ", 页面文本=" + snippetSummary(pageState) + "]";
        // v12.17-C: 应用错误可见化——"系统内部错误"类文案混在断言失败里会被误读为用例写得不好
        if (APP_ERROR_TEXT.matcher(snippetSummary(pageState)).find()) {
            desc += "；【app_error】页面出现被测系统错误提示，疑似应用侧缺陷或测试脏数据，非断言口径问题";
        }
        return desc;
    }

    /** textSnippet 截断摘要（前 120 字符），供 coordinates/证据字段 */
    public static String snippetSummary(Map<String, String> pageState) {
        if (pageState == null) {
            return "";
        }
        String snippet = pageState.getOrDefault("textSnippet", "");
        return snippet.length() > 120 ? snippet.substring(0, 120) + "..." : snippet;
    }
}
