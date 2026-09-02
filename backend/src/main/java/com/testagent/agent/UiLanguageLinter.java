package com.testagent.agent;

import com.testagent.dto.JsonHelper;
import com.testagent.entity.TestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v7.3(G20层2): UI 语言 lint。
 * 生成的用例 expected 常含"返回401""errorMsg""status=PENDING_PAYMENT"等 API 视角语言，
 * 步骤常含"调用取消收藏接口 POST /wx/collect/delete"接口化话术与 input_username/btn_login
 * 变量占位符——不是人类测试工程师的写法，且 UI 执行器无法执行（v9.2）。
 * 本 linter 以正则静态扫描打标记，零 LLM 成本，只标记不删改——
 * 结果写入 executionHints.uiLanguageViolations 供评审/前端提示。
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

    /** 规则4(v9.2): 接口化话术——"调用取消收藏接口" / "POST /wx/collect/delete"（UI 自动化禁止直接调接口） */
    private static final Pattern API_PHRASE = Pattern.compile(
            "(调用|请求|发起)[^。；，]{0,12}接口|\\b(GET|POST|PUT|DELETE|PATCH)\\s+/");

    /** 规则5(v9.2): 变量占位符/元素标识符——input_username / btn_login / valid_username / page_login */
    private static final Pattern SNAKE_ID = Pattern.compile(
            "\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");

    /** 12.21: expected 数量写死——'共 3 件收藏' 类句式（数字随数据变化必然脆断，改占位符 N） */

    /** v9.13: 不确定注释/或分叉特征——'（可能无此提示…）''可能出现…或操作无响应''或如果实际…' */
    private static final Pattern UNSURE_HINT = Pattern.compile(
            "可能无|可能出现|或如果|可能不|或操作无响应|若无此提示");
    private static final Pattern FIXED_COUNT_IN_EXPECTED = Pattern.compile(
            "共\\s*\\d+\\s*[件条个只项]");

    /** 规则6(v9.2): 步骤类型本身接口化——type=api_call（执行器一律 skip，生成即废步骤） */
    private static final String API_CALL_STEP = "api_call";

    /** 规则7(v9.6): 泛化跳转断言——触发跳转/开始跳转/页面跳转但无 URL/引号锚点/目标页 */
    private static final Pattern GENERIC_JUMP = Pattern.compile(
            "触发跳转|开始跳转|跳转成功|(?:页面|自动)跳转(?!至|到)");

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
        // ② 用例级自然语言步骤（v9.2：接口化/变量化话术在 steps 文本里同样禁止）
        List<String> textSteps = JsonHelper.parseListString(tc.getSteps());
        for (int i = 0; i < textSteps.size(); i++) {
            checkIdentifier(textSteps.get(i), "steps[" + i + "]", violations);
        }
        // ③ structuredSteps：action/target 查接口化与变量占位符；expected 查全部 UI 语言规则；
        //    type=api_call 本身即违规（v9.2 取消豁免——UI 自动化用例禁止直接调接口）
        List<Map<String, Object>> steps = JsonHelper.parseListMap(tc.getStructuredSteps());
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            String type = String.valueOf(step.get("type"));
            if (API_CALL_STEP.equals(type)) {
                violations.add("structuredSteps[" + i + "].type 'api_call' 为接口调用步骤，"
                        + "UI 自动化用例禁止——改为页面操作（点击/输入）+ 页面断言");
            }
            Object action = step.get("action");
            if (action != null && !String.valueOf(action).isBlank()) {
                checkIdentifier(String.valueOf(action), "structuredSteps[" + i + "].action", violations);
            }
            Object target = step.get("target");
            if (target != null && !String.valueOf(target).isBlank()) {
                checkIdentifier(String.valueOf(target), "structuredSteps[" + i + "].target", violations);
            }
            Object expected = step.get("expected");
            if (expected != null && !String.valueOf(expected).isBlank()) {
                check(String.valueOf(expected), "structuredSteps[" + i + "].expected", violations);
            }
            // v9.2: state_assert 必须引用页面可见文案（引号锚点），否则文本断言无法执行只会误报
            if ("state_assert".equals(type)) {
                Object exp = step.get("expected");
                if (exp != null && !String.valueOf(exp).isBlank()
                        && !com.testagent.service.ExecutionAssert.hasQuotedAnchor(String.valueOf(exp))) {
                    violations.add("structuredSteps[" + i + "].expected '" + truncate(String.valueOf(exp))
                            + "' 断言缺少引号引用的页面可见文案，文本断言无法执行"
                            + "（建议写法：页面显示'我的收藏'与商品价格）");
                }
            }
        }
        return violations;
    }

    /** action/target/steps 检查：接口化话术 + 变量占位符（HTTP 码/机器常量等 expected 规则不适用） */
    private static void checkIdentifier(String text, String field, List<String> violations) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher api = API_PHRASE.matcher(text);
        if (api.find()) {
            violations.add(field + " '" + truncate(text) + "' 疑似接口化步骤（" + api.group()
                    + "），UI 自动化应写页面操作（如 点击【取消收藏】按钮）");
            return;
        }
        Matcher snake = SNAKE_ID.matcher(text);
        if (snake.find()) {
            violations.add(field + " '" + truncate(text) + "' 疑似变量占位符/元素标识符（" + snake.group()
                    + "），应写人类可读名称（如 用户名输入框）");
        }
    }

    private static void check(String text, String field, List<String> violations) {
        if (text == null || text.isBlank()) {
            return;
        }
        // 12.21: 数量写死——'共 3 件收藏' 的数字随数据变化必然脆断（litemall 实测写死 3/5 全部
        // 失败），应改占位符 '共 N 件收藏'（执行器按数字语义匹配）
        if (field.contains("expected")) {
            Matcher fixedCount = FIXED_COUNT_IN_EXPECTED.matcher(text);
            if (fixedCount.find()) {
                violations.add(field + " '" + truncate(text) + "' 数量写死（" + fixedCount.group()
                        + "）——数字随数据变化必然失败，应改占位符表达如'共 N 件收藏'");
                return;
            }
        }
        // v9.13: 引号文案以中文+数字结尾——'我的收藏 3' 徽标形态，数字随数据变化（"共 N 件"
        // 模式覆盖不到）；确认性提示，建议改占位符或引不含数字的真实文案
        for (String q : com.testagent.service.ExecutionAssert.quotedPhrases(text)) {
            if (q.matches(".*[一-龥]\\s*\\d+")) {
                violations.add(field + " '" + truncate(text) + "' 引号文案含写死数字（" + q
                        + "）——数字随数据变化，应改占位符表达或引用不含数字的真实文案");
                return;
            }
        }
        // v9.13: 不确定注释/或分叉——'（可能无此提示…）''可能出现…或操作无响应'写进了 expected
        Matcher unsure = UNSURE_HINT.matcher(text);
        if (unsure.find()) {
            violations.add(field + " '" + truncate(text) + "' 含不确定表述（" + unsure.group()
                    + "）——行为不确定时应对照 userFeedbackTexts 写确定的单一结果，或删除该断言");
            return;
        }
        // v9.4: 引号文案占位符——执行器已支持占位符数字语义匹配（'共 N 件收藏' 匹配"共 1 件收藏"），
        // 不再是违规；降级为确认性提示，提醒检查占位符写法为 N 单字符形态
        List<String> badQuotes = com.testagent.service.ExecutionAssert.quotedPlaceholders(text);
        if (!badQuotes.isEmpty()) {
            violations.add(field + " '" + truncate(text) + "' 引号文案含占位符（" + badQuotes.get(0)
                    + "）——执行器按数字语义匹配，请确认占位符为单字符 N/X 形态且表述为'共 N 件'类数量句式");
            return;
        }
        Matcher api = API_PHRASE.matcher(text);
        if (api.find()) {
            violations.add(field + " '" + truncate(text) + "' 疑似接口化表述（" + api.group()
                    + "），应写页面可感知现象");
            return;
        }
        Matcher jump = GENERIC_JUMP.matcher(text);
        if (jump.find() && !text.contains("URL") && !text.contains("包含")
                && !com.testagent.service.ExecutionAssert.hasQuotedAnchor(text)) {
            violations.add(field + " '" + truncate(text) + "' 跳转断言为不可验证泛化表述（"
                    + jump.group() + "），应写 页面URL包含'/xxx' 或目标页可见标题");
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
            return;
        }
        Matcher snake = SNAKE_ID.matcher(text);
        if (snake.find()) {
            violations.add(field + " '" + truncate(text) + "' 疑似变量占位符/元素标识符（" + snake.group()
                    + "），应写人类可读名称");
        }
    }

    private static String truncate(String text) {
        return text.length() > 40 ? text.substring(0, 40) + "..." : text;
    }
}
