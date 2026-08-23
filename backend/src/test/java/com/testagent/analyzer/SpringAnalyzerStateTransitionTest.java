package com.testagent.analyzer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.6(A17): SpringAnalyzer 状态转换证据提取测试（规则层，零 LLM 成本）。
 * 业务背景：扫描 setStatus(X)/status=X 赋值点，从方法上下文提取"转换来源→目标"证据，
 * 作为状态机 transitions 的 ground truth。
 */
class SpringAnalyzerStateTransitionTest {

    private SpringAnalyzer analyzer() {
        return new SpringAnalyzer();
    }

    @Test
    void conditionalSetStatusYieldsFromAndTo() {
        // if (getStatus() == CREATED) { setStatus(PAID); } → from=CREATED, to=PAID
        CompilationUnit cu = StaticJavaParser.parse("""
                class OrderService {
                    public void payOrder(Order order) {
                        if (order.getStatus() == OrderStatus.CREATED) {
                            order.setStatus(OrderStatus.PAID);
                        }
                    }
                }
                """);

        List<Map<String, Object>> evidence = analyzer().extractStateTransitions(cu, "OrderService.java");

        assertEquals(1, evidence.size());
        Map<String, Object> ev = evidence.get(0);
        assertEquals("PAID", ev.get("to"));
        assertEquals("CREATED", ev.get("from"));
        assertEquals("payOrder", ev.get("method"));
        assertEquals("status", ev.get("field"));
    }

    @Test
    void unconditionalSetStatusYieldsWildcardFrom() {
        // 无条件判断 → from="*"（任意状态可达）
        CompilationUnit cu = StaticJavaParser.parse("""
                class OrderService {
                    public void cancelOrder(Order order) {
                        order.setStatus(OrderStatus.CANCELLED);
                    }
                }
                """);

        List<Map<String, Object>> evidence = analyzer().extractStateTransitions(cu, "OrderService.java");

        assertEquals(1, evidence.size());
        assertEquals("CANCELLED", evidence.get(0).get("to"));
        assertEquals("*", evidence.get(0).get("from"));
    }

    @Test
    void directFieldAssignmentIsCollected() {
        // status = SHIPPED 直接赋值
        CompilationUnit cu = StaticJavaParser.parse("""
                class Shipment {
                    private OrderStatus status;
                    public void ship() {
                        if (this.status == OrderStatus.PAID) {
                            this.status = OrderStatus.SHIPPED;
                        }
                    }
                }
                """);

        List<Map<String, Object>> evidence = analyzer().extractStateTransitions(cu, "Shipment.java");

        assertEquals(1, evidence.size());
        assertEquals("SHIPPED", evidence.get(0).get("to"));
        assertEquals("PAID", evidence.get(0).get("from"));
    }

    @Test
    void equalsFormConditionIsRecognized() {
        // PAID.equals(order.getStatus()) 形态的条件
        CompilationUnit cu = StaticJavaParser.parse("""
                class OrderService {
                    public void refund(Order order) {
                        if (OrderStatus.PAID.equals(order.getStatus())) {
                            order.setStatus(OrderStatus.REFUNDED);
                        }
                    }
                }
                """);

        List<Map<String, Object>> evidence = analyzer().extractStateTransitions(cu, "OrderService.java");

        assertEquals(1, evidence.size());
        assertEquals("REFUNDED", evidence.get(0).get("to"));
        assertEquals("PAID", evidence.get(0).get("from"));
    }

    @Test
    void variableAssignmentIsIgnored() {
        // setStatus(someVariable) 无法静态确定 → 不产生证据（宁缺勿滥）
        CompilationUnit cu = StaticJavaParser.parse("""
                class OrderService {
                    public void update(Order order, OrderStatus next) {
                        order.setStatus(next);
                    }
                }
                """);

        List<Map<String, Object>> evidence = analyzer().extractStateTransitions(cu, "OrderService.java");

        assertTrue(evidence.isEmpty(), "变量赋值无法静态确定目标状态，不应产生证据");
    }

    @Test
    void nonStateSetterIsIgnored() {
        // setName/setEnabled 等非状态 setter 不收集
        CompilationUnit cu = StaticJavaParser.parse("""
                class UserService {
                    public void rename(User user) {
                        user.setName("bob");
                        user.setEnabled(true);
                    }
                }
                """);

        List<Map<String, Object>> evidence = analyzer().extractStateTransitions(cu, "UserService.java");

        assertTrue(evidence.isEmpty());
    }

    @Test
    void multipleConditionsYieldMultipleEvidence() {
        // 两个条件分支各产生一条证据
        CompilationUnit cu = StaticJavaParser.parse("""
                class OrderService {
                    public void transit(Order order) {
                        if (order.getStatus() == OrderStatus.CREATED) {
                            order.setStatus(OrderStatus.PAID);
                        } else if (order.getStatus() == OrderStatus.PAID) {
                            order.setStatus(OrderStatus.COMPLETED);
                        }
                    }
                }
                """);

        List<Map<String, Object>> evidence = analyzer().extractStateTransitions(cu, "OrderService.java");

        assertEquals(2, evidence.size());
        assertTrue(evidence.stream().anyMatch(e ->
                "CREATED".equals(e.get("from")) && "PAID".equals(e.get("to"))));
        assertTrue(evidence.stream().anyMatch(e ->
                "PAID".equals(e.get("from")) && "COMPLETED".equals(e.get("to"))));
    }
}
