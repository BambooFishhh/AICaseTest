package com.testagent.analyzer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v13.5(点1第2条): compactMethodBody 结构感知压缩单测。
 *
 * 背景：旧实现把方法体空白折叠后硬截 300 字符——长方法只剩开头 Happy Path 序言，
 * 守卫/分支逻辑全丢，且拦腰断在词法中间；下游规则摘要(200)/ScopeMapping(80)的
 * 头部截断窗口只能拿到这段低价值前缀。
 * 此处验证：
 *   1. ≤500 字符全文保留（300~500 区间不再被截，旧行为会丢尾）；
 *   2. 超限时优先保留控制流/校验信号行（守卫在前→下游头切窗口信号密集）；
 *   3. 无信号行/单超长信号行兜底退回头部截断，永不为空；
 *   4. 任意情况结果都以完整行为单位（头部截断兜底除外，与旧行为一致）。
 */
class SpringAnalyzerCompactMethodBodyTest {

    private final SpringAnalyzer analyzer = new SpringAnalyzer();

    private String compactOf(String methodSrc) {
        CompilationUnit cu = StaticJavaParser.parse("class Demo {\n" + methodSrc + "\n}");
        MethodDeclaration md = cu.findAll(MethodDeclaration.class).get(0);
        return analyzer.compactMethodBody(md);
    }

    /** 无关键字样板行 n 条，每行约 33 字符 */
    private static String boilerplate(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append("    value").append(i).append(" = computeItem").append(i)
                    .append("(src").append(i).append(");\n");
        }
        return sb.toString();
    }

    /** 短方法：原样保留，不追加省略号 */
    @Test
    void shortBodyKeptInFull() {
        String out = compactOf("int add(int a, int b) { return a + b; }");
        assertEquals("{ return a + b; }", out);
    }

    /** 300~500 区间：旧代码在 300 硬截丢尾，新代码全文保留 */
    @Test
    void mediumBodyBetween300And500NoLongerTruncated() {
        String out = compactOf("Object demo() {\n" + boilerplate(12) + "}");
        assertFalse(out.contains("..."), "≤500 字符不应截断: " + out.length());
        assertTrue(out.contains("value11 = computeItem11(src11);"),
                "最后一行应保留（旧行为会丢）");
    }

    /** 超限长方法：守卫/校验/返回行保留且在最前，纯样板行被丢 */
    @Test
    void longMethodKeepsSignalLinesFirst() {
        String out = compactOf("Object demo() {\n"
                + "    if (userId == null) return ResponseUtil.unlogin();\n"
                + boilerplate(30)
                + "    if (stock < num) return ResponseUtil.fail(400, \"库存不足\");\n"
                + "    return ResponseUtil.ok(data);\n"
                + "}");
        assertTrue(out.endsWith("..."));
        assertTrue(out.length() <= SpringAnalyzer.METHOD_BODY_MAX_CHARS + 3,
                "输出不得超过上限: " + out.length());
        assertTrue(out.startsWith("if (userId == null)"),
                "守卫应排最前（下游 200/80 头切窗口信号密集）: " + out);
        assertTrue(out.contains("if (stock < num) return ResponseUtil.fail(400, \"库存不足\");"),
                "校验行应完整保留");
        assertTrue(out.contains("return ResponseUtil.ok(data);"), "返回行应完整保留");
        assertFalse(out.contains("computeItem15"), "中段纯样板行应被丢弃");
    }

    /** 无任何信号行的超限方法：兜底退回旧行为（头部截断），非空 */
    @Test
    void noSignalLinesFallsBackToHeadCut() {
        String out = compactOf("Object demo() {\n" + boilerplate(30) + "}");
        assertEquals(SpringAnalyzer.METHOD_BODY_MAX_CHARS + 3, out.length());
        assertTrue(out.endsWith("..."));
        assertTrue(out.startsWith("{ value0"), "头部截断应从方法体开头开始: " + out);
        assertTrue(out.contains("computeItem0"));
        assertFalse(out.contains("computeItem25"), "尾部内容应被截掉");
    }

    /** 单条超长信号行（>500，600 字符标识符无法被打印器换行拆分）：按行选择放不下 → 兜底截断信号串，非空 */
    @Test
    void singleHugeSignalLineFallsBackToTruncatedSignals() {
        String out = compactOf("Object demo() {\n    return " + "a".repeat(600) + ";\n}");
        assertEquals(SpringAnalyzer.METHOD_BODY_MAX_CHARS + 3, out.length());
        assertTrue(out.endsWith("..."));
        assertTrue(out.startsWith("return "), "兜底应从信号串头部截断: " + out);
    }

    /** 空方法体返回空串（回归） */
    @Test
    void emptyBodyReturnsEmpty() {
        assertEquals("", compactOf("void demo();"));
    }
}
