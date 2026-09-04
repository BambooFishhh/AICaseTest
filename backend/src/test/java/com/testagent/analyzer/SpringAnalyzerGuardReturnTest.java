package com.testagent.analyzer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.testagent.analyzer.result.BusinessRule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v13.2: if-return 型校验规则（guard_return）单测。
 *
 * 背景：extractBusinessRules 原先要求 if 体内同时含 throw 与 new，
 * 而权限/参数校验的主流写法是 if (...) return ResponseUtil.fail(...)——不含 throw，
 * 一条都收不到，只能靠 LLM 补充。此处验证：
 *   1. 带错误语义的 if-return 能被规则层直采（两种块形态）；
 *   2. 纯逻辑分支 / 空值防御不被误收（延续 v7.10 A6 的防噪音精神）；
 *   3. 原有 throw 型规则与噪音异常过滤行为不变（回归）。
 */
class SpringAnalyzerGuardReturnTest {

    private final SpringAnalyzer analyzer = new SpringAnalyzer();

    private List<BusinessRule> rulesOf(String methodBody) {
        String src = "class Demo {\n" + methodBody + "\n}";
        CompilationUnit cu = StaticJavaParser.parse(src);
        return analyzer.extractBusinessRules(cu, "Demo.java", new ArrayList<>());
    }

    /** 权限校验：非 throw 写法，修复前一条都收不到 */
    @Test
    void permissionGuardReturnIsCollected() {
        List<BusinessRule> rules = rulesOf(
                "  void update(java.util.Map u) {\n"
                        + "    if (!isAdmin(u)) return ResponseUtil.fail(403, \"无权限\");\n"
                        + "  }");

        assertEquals(1, rules.size(), "权限校验的 if-return 应被规则层直采");
        assertEquals("guard_return", rules.get(0).getRuleType());
        assertTrue(rules.get(0).getRule().contains("isAdmin"),
                "规则描述应保留条件: " + rules.get(0).getRule());
        assertTrue(rules.get(0).getRule().contains("fail"),
                "规则描述应保留返回表达式: " + rules.get(0).getRule());
    }

    /** 块形态（then 是仅含一条 return 的 BlockStmt）也应收录——litemall 主流写法 */
    @Test
    void guardReturnInsideBlockIsCollected() {
        List<BusinessRule> rules = rulesOf(
                "  void create(String name) {\n"
                        + "    if (name == null || name.isEmpty()) {\n"
                        + "      return ResponseUtil.badArgument();\n"
                        + "    }\n"
                        + "  }");

        assertEquals(1, rules.size(), "块形态的 if-return 校验也应收录");
        assertEquals("guard_return", rules.get(0).getRuleType());
    }

    /** 库存/业务校验（同属非 throw 的错误返回） */
    @Test
    void businessGuardReturnIsCollected() {
        List<BusinessRule> rules = rulesOf(
                "  Object buy(int stock, int num) {\n"
                        + "    if (stock < num) return ResponseUtil.fail(400, \"库存不足\");\n"
                        + "  }");

        assertEquals(1, rules.size());
        assertEquals("guard_return", rules.get(0).getRuleType());
    }

    /** 噪音防线 1：纯逻辑分支不得被当成业务规则 */
    @Test
    void pureLogicReturnIsIgnored() {
        assertEquals(0, rulesOf(
                        "  int max(int a, int b) {\n"
                                + "    if (a > b) return a;\n"
                                + "    return b;\n"
                                + "  }").size(),
                "if (a > b) return a 是纯逻辑，不应收录为业务规则");
    }

    /** 噪音防线 2：空值防御 return null 不得收录 */
    @Test
    void nullGuardReturnIsIgnored() {
        assertEquals(0, rulesOf(
                        "  Object find(String id) {\n"
                                + "    if (id == null) return null;\n"
                                + "    return new Object();\n"
                                + "  }").size(),
                "return null 的空值防御不构成业务规则");
    }

    /**
     * 噪音防线 3：方法名"包含"但非"以信号词开头"的调用不得误伤
     * （早期正则带 \\w* 前缀会把 getFailureRate 判成错误返回）
     */
    @Test
    void methodNameContainingSignalWordIsNotMisclassified() {
        assertEquals(0, rulesOf(
                        "  double stat(User u) {\n"
                                + "    if (u == null) return getFailureRate();\n"
                                + "    return 0.0;\n"
                                + "  }").size(),
                "getFailureRate() 只是含 fail 字样，不是错误返回，不得误收");
    }

    /** 回归：throw 型业务规则仍照常收录 */
    @Test
    void throwRuleStillCollected() {
        List<BusinessRule> rules = rulesOf(
                "  void pay(int amount) {\n"
                        + "    if (amount <= 0) throw new BizException(\"金额非法\");\n"
                        + "  }");

        assertEquals(1, rules.size());
        assertEquals("throw_exception", rules.get(0).getRuleType());
    }

    /** 回归：v7.10(A6) 噪音异常过滤仍然生效 */
    @Test
    void noiseExceptionStillFiltered() {
        assertEquals(0, rulesOf(
                        "  void check(String s) {\n"
                                + "    if (s == null) throw new IllegalArgumentException(\"s required\");\n"
                                + "  }").size(),
                "IllegalArgumentException 属通用异常，应继续被过滤");
    }

    /** 混合场景：throw 型与 guard_return 型各自归类，互不干扰 */
    @Test
    void mixedRulesAreClassifiedSeparately() {
        List<BusinessRule> rules = rulesOf(
                "  Object handle(User u, int amount) {\n"
                        + "    if (u == null) throw new IllegalArgumentException(\"u\");\n"
                        + "    if (!u.isAdmin()) return ResponseUtil.fail(403, \"无权限\");\n"
                        + "    if (amount <= 0) throw new BizException(\"金额非法\");\n"
                        + "    return null;\n"
                        + "  }");

        long guards = rules.stream().filter(r -> "guard_return".equals(r.getRuleType())).count();
        long throws_ = rules.stream().filter(r -> "throw_exception".equals(r.getRuleType())).count();
        assertEquals(1, guards, "仅 1 条 if-return 校验");
        assertEquals(1, throws_, "仅 1 条业务异常（另一条是噪音异常被过滤）");
    }
}
