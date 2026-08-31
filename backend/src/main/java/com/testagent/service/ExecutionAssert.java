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

        // 层 1: URL/标题语义
        if (ASSERT_URLISH.matcher(expected).find()) {
            String url = pageState.getOrDefault("url", "");
            String title = pageState.getOrDefault("title", "");
            String urlTitleText = (url + " " + title).toLowerCase();
            List<String> keywords = new ArrayList<>();
            Matcher m = ASSERT_TOKEN.matcher(expected);
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
        List<String> quoted = extractQuoted(expected);
        List<String> segments = new ArrayList<>();
        Matcher zh = ASSERT_CHINESE.matcher(expected);
        while (zh.find()) {
            segments.add(zh.group());
        }
        // 进入文本断言的前提：有引号短语或中文段——纯 API 形态（status=XXX）无 UI 可比内容
        if (quoted.isEmpty() && segments.isEmpty()) {
            return "skipped";
        }
        String snippet = pageState.getOrDefault("textSnippet", "");
        String title = pageState.getOrDefault("title", "");
        if (snippet.isBlank()) {
            return "skipped";  // 无 DOM 文本快照（textSnippet 缺失/为空）→ 文本断言不可执行，title 不是页面正文
        }
        String pageText = (title + " " + snippet).toLowerCase();

        // 引号短语：完整包含；含占位符（N/X/{var}）的短语按"占位符=数字"正则语义匹配
        for (String q : quoted) {
            if (QUOTE_PLACEHOLDER.matcher(q).find()) {
                if (!placeholderPhraseRegex(q).matcher(pageText).find()) {
                    return "failed";
                }
            } else if (!pageText.contains(q.toLowerCase())) {
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
        Matcher en = ASSERT_TEXT_TOKEN.matcher(expected);
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

    /** v9.2: 含占位符的引号短语 → 正则：占位符（N/X/{var}）匹配数字，字面段原样匹配（大小写不敏感）。
     *  如 '共 N 件收藏' → /共 \d+ 件收藏/，页面显示"共 1 件收藏"即通过。 */
    private static java.util.regex.Pattern placeholderPhraseRegex(String phrase) {
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

    /**
     * 期望/实际差异摘要，供 ExecutionStep.error 字段（断言 failed 时使用）。
     */
    public static String describe(String expected, Map<String, String> pageState) {
        if (pageState == null) {
            return "断言不匹配: 期望[" + expected + "] 实际页面状态读取失败";
        }
        return "断言不匹配: 期望[" + expected + "] 实际[url="
                + pageState.getOrDefault("url", "") + ", title=" + pageState.getOrDefault("title", "")
                + ", 页面文本=" + snippetSummary(pageState) + "]";
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
