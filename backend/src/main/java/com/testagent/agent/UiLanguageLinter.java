package com.testagent.agent;

import com.testagent.dto.JsonHelper;
import com.testagent.entity.TestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v7.3(G20层2): 预期结果 UI 语言 lint。
 * 生成的用例 expected 常含"返回401""errorMsg""status=PENDING_PAYMENT"等 API 视角语言，
 * 不是用户在页面上可感知的现象（病根见风险清单 G20）。本 linter 以正则静态扫描打标记，
 * 零 LLM 成本，只标记不删改——结果写入 executionHints.uiLanguageViolations 供评审/前端提示。
 * 规则保守设计：宁可漏报不误报刷屏。
 */
public final class UiLanguageLinter {

    private UiLanguageLinter() {
    }

    /** 规则1: HTTP 状态码形态——"返回401" / "响应码：403" / "HTTP 500错误" / "500 状态码" */
    private static final Pattern HTTP_CODE = Pattern.compile(
            "(返回|响应|状态码|HTTP|http)[^\\d]{0,4}[45]\\d{2}|[45]\\d{2}\\s*(错误|状态码)");

    /** 规则2: 机器常量——全大写下划线 ≥2 段（PENDING_PAYMENT / ERROR_CODE） */
    private static final Pattern UPPER_CONST = Pattern.compile("\\b[A-Z]{2,}(?:_[A-Z0-9]+)+\\b");

    /** 规则3: 后端字段赋值——"errorMsg=参数缺失" / "status: ERROR" / "code=10001" */
    private static final Pattern FIELD_ASSIGN = Pattern.compile(
            "\\b(errMsg|errorMsg|status|code|message|respCode|errCode)\\s*[:=]\\s*\\S+");

    /** api_call 步骤允许接口语义，不 lint；其余步骤类型按 UI 语言要求检查 */
    private static final String API_CALL_STEP = "api_call";

    public static List<String> lint(TestCase tc) {
        List<String> violations = new ArrayList<>();
        if (tc == null) {
            return violations;
        }
        // ① expectedResults（用例级预期，必须是页面可感知现象）
        List<String> expectedResults = JsonHelper.parseListString(tc.getExpectedResults());
        for (int i = 0; i < expectedResults.size(); i++) {
            check(expectedResults.get(i), "expectedResults[" + i + "]", violations);
        }
        // ② structuredSteps 中非 api_call 步骤的 expected
        List<Map<String, Object>> steps = JsonHelper.parseListMap(tc.getStructuredSteps());
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            if (API_CALL_STEP.equals(String.valueOf(step.get("type")))) {
                continue;
            }
            Object expected = step.get("expected");
            if (expected != null && !String.valueOf(expected).isBlank()) {
                check(String.valueOf(expected), "structuredSteps[" + i + "].expected", violations);
            }
        }
        return violations;
    }

    private static void check(String text, String field, List<String> violations) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher http = HTTP_CODE.matcher(text);
        if (http.find()) {
            violations.add(field + " '" + truncate(text) + "' 疑似HTTP状态码，应写页面可感知现象（如提示文案/跳转）");
            return;
        }
        Matcher upper = UPPER_CONST.matcher(text);
        if (upper.find()) {
            violations.add(field + " '" + truncate(text) + "' 疑似机器常量（" + upper.group()
                    + "），应写用户可见文案");
            return;
        }
        Matcher assign = FIELD_ASSIGN.matcher(text);
        if (assign.find()) {
            violations.add(field + " '" + truncate(text) + "' 疑似后端字段赋值（" + assign.group()
                    + "），应写页面可感知现象");
        }
    }

    private static String truncate(String text) {
        return text.length() > 40 ? text.substring(0, 40) + "..." : text;
    }
}
