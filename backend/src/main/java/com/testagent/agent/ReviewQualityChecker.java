package com.testagent.agent;

import com.testagent.dto.JsonHelper;
import com.testagent.entity.TestCase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * v9.6: 评审层静态质量检查——语义重复 + 预期与动作一致性。
 *
 * 零 LLM 成本，在 LLM 评审前先打标记：
 * - 语义重复：同模块同类型、结构化步骤/断言高度重叠的不同标题用例；
 * - 预期一致性：删除/取消动作不得只断言页面标题；跳转断言不得用无锚点的泛化表述。
 *
 * 规则保守设计：只标记不删改，避免正向/异常成对用例误判与自动删除误伤。
 */
public final class ReviewQualityChecker {

    private ReviewQualityChecker() {
    }

    private static final double DUPLICATE_THRESHOLD = 0.85;

    /**
     * 泛化跳转：必须带 URL/引号锚点/目标页，否则不可验证。
     * "页面跳转至首页"、"自动跳转至登录页" 这类带目标的不命中。
     */
    private static final Pattern GENERIC_JUMP = Pattern.compile(
            "触发跳转|开始跳转|跳转成功|(?:页面|自动)跳转(?!至|到)");

    /** v9.6: 删除/取消/移除类动作，预期必须落在动作结果上 */
    private static final Pattern MUTATING_ACTION = Pattern.compile("删除|取消|移除");

    /** 动作结果关键词：提示/列表/总数/消失/成功/已等，标题样式断言不含这些词时判懒断言 */
    private static final Pattern EFFECT_KEYWORDS = Pattern.compile(
            "提示|消失|减少|增加|成功|失败|已|列表|总数|数量|保持不变|选中|结果|文案");

    private static final Pattern TITLE_ONLY = Pattern.compile(
            "页面显示'[^']+'标题|页面标题显示'[^']+'|页面标题为'[^']+'|标题显示'[^']+'");

    /** 行为签名的通用噪声词，归一后不参与相似度计算 */
    private static final Pattern GENERIC_NOISE = Pattern.compile(
            "正确|正常|成功|失败|并|且|与|的|了|后|时|再|页面|入口|展示|跳转|点击|进入|显示|按钮|"
                    + "列表|商品|总数|数量|返回|打开|验证|查看|自动|触发|开始|至|到|标题|当前|内容|区域");

    public static Map<String, List<String>> semanticDuplicates(List<TestCase> cases) {
        Map<String, List<String>> issues = new LinkedHashMap<>();
        if (cases == null || cases.size() < 2) {
            return issues;
        }
        for (int i = 0; i < cases.size(); i++) {
            for (int j = i + 1; j < cases.size(); j++) {
                TestCase a = cases.get(i);
                TestCase b = cases.get(j);
                if (a == null || b == null || !sameModuleType(a, b)) {
                    continue;
                }
                double sim = behaviorSimilarity(a, b);
                if (sim < DUPLICATE_THRESHOLD) {
                    continue;
                }
                String nameA = titleOf(a);
                String nameB = titleOf(b);
                String msg = "语义重复：与「%s」同模块同类型，步骤/断言高度重叠（相似度 %.2f），"
                        + "建议合并保留断言更明确的一条".formatted(
                                nameA.equals(nameB) ? "另一条用例" : nameB, sim);
                issues.computeIfAbsent(nameA, k -> new ArrayList<>()).add(msg);
                issues.computeIfAbsent(nameB, k -> new ArrayList<>()).add(
                        "语义重复：与「%s」同模块同类型，步骤/断言高度重叠（相似度 %.2f），"
                                + "建议合并保留断言更明确的一条".formatted(nameA, sim));
            }
        }
        return issues;
    }

    public static List<String> expectedActionConsistency(TestCase tc) {
        List<String> issues = new ArrayList<>();
        if (tc == null) {
            return issues;
        }
        List<Map<String, Object>> steps = JsonHelper.parseListMap(tc.getStructuredSteps());
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            String action = str(step.get("action"));
            String expected = str(step.get("expected"));

            // 1. 泛化跳转断言没有可验证锚点
            if (!expected.isBlank() && GENERIC_JUMP.matcher(expected).find()
                    && !expected.contains("URL")
                    && !expected.contains("包含")
                    && expected.indexOf('\'') < 0) {
                issues.add("structuredSteps[" + i + "].expected '" + truncate(expected)
                        + "' 跳转断言为不可验证泛化表述，应写 页面URL包含'/xxx' 或目标页可见标题");
            }

            // 2. 删除/取消类动作：动作自身与后续断言都不得只断言页面标题
            boolean mutating = MUTATING_ACTION.matcher(action).find()
                    && !action.matches(".*(验证|检查|断言|查看|记录).*");
            if (mutating) {
                if (!expected.isBlank() && TITLE_ONLY.matcher(expected).find()
                        && !EFFECT_KEYWORDS.matcher(expected).find()) {
                    issues.add("structuredSteps[" + i + "].expected '" + truncate(expected)
                            + "' 与动作'" + truncate(action) + "'不匹配：删除/取消类动作的预期必须验证动作结果"
                            + "（列表消失/总数更新/提示文案），不得只断言页面标题");
                }
                if (i + 1 < steps.size()) {
                    Map<String, Object> next = steps.get(i + 1);
                    if ("state_assert".equals(str(next.get("type")))) {
                        String nextExpected = str(next.get("expected"));
                        if (!nextExpected.isBlank() && TITLE_ONLY.matcher(nextExpected).find()
                                && !EFFECT_KEYWORDS.matcher(nextExpected).find()) {
                            issues.add("structuredSteps[" + (i + 1) + "].expected '" + truncate(nextExpected)
                                    + "' 未验证删除/取消动作结果，只断言页面标题");
                        }
                    }
                }
            }
        }
        return issues;
    }

    private static boolean sameModuleType(TestCase a, TestCase b) {
        String typeA = a.getType();
        String typeB = b.getType();
        if (typeA == null || typeB == null || !typeA.equals(typeB)) {
            return false;
        }
        String modA = a.getModule();
        String modB = b.getModule();
        return modA != null && modB != null && modA.equals(modB);
    }

    private static double behaviorSimilarity(TestCase a, TestCase b) {
        Set<String> setA = behaviorTokens(a);
        Set<String> setB = behaviorTokens(b);
        if (setA.isEmpty() || setB.isEmpty()) {
            return 0;
        }
        int inter = 0;
        for (String token : setA) {
            if (setB.contains(token)) {
                inter++;
            }
        }
        int union = setA.size() + setB.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    /** 行为 token：结构化步骤 type/action/target/expected 归一后的字符二元组集合 */
    private static Set<String> behaviorTokens(TestCase tc) {
        StringBuilder signature = new StringBuilder();
        List<Map<String, Object>> steps = JsonHelper.parseListMap(tc.getStructuredSteps());
        for (Map<String, Object> step : steps) {
            String type = str(step.get("type"));
            String action = str(step.get("action"));
            String target = str(step.get("target"));
            String expected = str(step.get("expected"));
            signature.append(type).append('|')
                    .append(normalize(action)).append('|')
                    .append(normalize(target)).append('|')
                    .append(normalize(expected)).append(';');
        }
        String text = normalize(signature.toString());
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i + 1 < text.length(); i++) {
            char c1 = text.charAt(i);
            char c2 = text.charAt(i + 1);
            if (Character.isLetterOrDigit(c1) || Character.isLetterOrDigit(c2)) {
                tokens.add(new String(new char[]{c1, c2}));
            }
        }
        return tokens;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String t = text.toLowerCase().replaceAll("\\s+", "");
        t = t.replaceAll("[NnXx]\\d*|\\d+", "N");
        t = GENERIC_NOISE.matcher(t).replaceAll("").trim();
        return t;
    }

    private static String titleOf(TestCase tc) {
        return tc.getTitle() == null || tc.getTitle().isBlank() ? "(未命名)" : tc.getTitle().trim();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String truncate(String text) {
        return text.length() > 40 ? text.substring(0, 40) + "..." : text;
    }
}
