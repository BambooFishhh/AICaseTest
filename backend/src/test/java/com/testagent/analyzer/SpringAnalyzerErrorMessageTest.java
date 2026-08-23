package com.testagent.analyzer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.6(G20层3): SpringAnalyzer 异常用户消息提取测试。
 * 业务背景：expected 里的"返回 401""提示 errorMsg"不是用户可感知现象；
 * 后端异常 message 字面量与前端 ElMessage 文案合成"错误→用户文案"对照表。
 */
class SpringAnalyzerErrorMessageTest {

    private SpringAnalyzer analyzer() {
        return new SpringAnalyzer();
    }

    @Test
    void exceptionMessageLiteralIsExtracted() {
        CompilationUnit cu = StaticJavaParser.parse("""
                class OrderService {
                    public void cancel(String orderId) {
                        if (orderId == null) {
                            throw new BusinessException("订单不存在");
                        }
                    }
                }
                """);

        List<Map<String, Object>> messages = analyzer().extractErrorMessages(cu, "OrderService.java");

        assertEquals(1, messages.size());
        assertEquals("BusinessException", messages.get(0).get("exception"));
        assertEquals("订单不存在", messages.get(0).get("message"));
    }

    @Test
    void concatenatedLiteralIsMerged() {
        // "库存" + "不足" 纯字面量拼接 → 合并为一条消息
        CompilationUnit cu = StaticJavaParser.parse("""
                class StockService {
                    public void check(int qty) {
                        throw new BizException("库存" + "不足");
                    }
                }
                """);

        List<Map<String, Object>> messages = analyzer().extractErrorMessages(cu, "StockService.java");

        assertEquals(1, messages.size());
        assertEquals("库存不足", messages.get(0).get("message"));
    }

    @Test
    void variableMessageIsIgnored() {
        // throw new BizException(variable) / 拼接变量 → 无法确定用户文案，不收集
        CompilationUnit cu = StaticJavaParser.parse("""
                class OrderService {
                    public void cancel(String orderId, String reason) {
                        throw new BizException(reason);
                    }
                    public void fail(int code) {
                        throw new BizException("错误码: " + code);
                    }
                }
                """);

        List<Map<String, Object>> messages = analyzer().extractErrorMessages(cu, "OrderService.java");

        assertTrue(messages.isEmpty(), "变量/含变量拼接的消息不应收集");
    }

    @Test
    void tooLongOrTooShortMessageIsIgnored() {
        String source = """
                class OrderService {
                    public void a() { throw new BizException("x"); }
                    public void b() { throw new BizException("%s"); }
                }
                """.formatted("a".repeat(120));
        CompilationUnit cu = StaticJavaParser.parse(source);

        List<Map<String, Object>> messages = analyzer().extractErrorMessages(cu, "OrderService.java");

        assertTrue(messages.isEmpty(), "长度 1 或超 100 的消息不收集");
    }

    @Test
    void rethrowWithoutNewIsIgnored() {
        CompilationUnit cu = StaticJavaParser.parse("""
                class OrderService {
                    public void wrap(Exception e) throws Exception {
                        throw e;
                    }
                }
                """);

        List<Map<String, Object>> messages = analyzer().extractErrorMessages(cu, "OrderService.java");

        assertTrue(messages.isEmpty());
    }
}
