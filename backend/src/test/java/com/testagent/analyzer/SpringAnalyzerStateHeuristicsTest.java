package com.testagent.analyzer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.testagent.analyzer.result.BusinessRule;
import com.testagent.analyzer.result.EndpointInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.10: SpringAnalyzer 启发式扩展单测（规则层，零 LLM 成本）。
 * A3: endpoint 异常收集方法内全部 throw（旧实现只看第一个 throw 的第一个 new），上限 5。
 * A6: 业务规则只收录业务语义异常（空指针防御/参数断言过滤，过滤量进 warnings）。
 * A18: 状态启发式扩展——Integer/String 字面量状态值、state/type 字段名。
 */
class SpringAnalyzerStateHeuristicsTest {

    private final SpringAnalyzer analyzer = new SpringAnalyzer();

    // ==================== A3: 多异常收集 ====================

    @Test
    void allThrownExceptionTypesCollected() {
        CompilationUnit cu = StaticJavaParser.parse("""
                import org.springframework.web.bind.annotation.*;
                @RestController
                class OrderController {
                    @GetMapping("/orders/{id}")
                    public String getOrder(Long id) {
                        if (id == null) throw new IllegalArgumentException("id required");
                        if (id < 0) throw new BusinessException("订单不存在");
                        if (id > 9999) throw new BusinessException("超出范围");
                        throw new PermissionDeniedException("无权限");
                    }
                }
                """);

        List<EndpointInfo> endpoints = analyzer.extractEndpoints(cu, "OrderController.java");

        assertEquals(1, endpoints.size());
        List<String> exceptions = endpoints.get(0).getExceptions();
        assertTrue(exceptions.containsAll(List.of(
                "IllegalArgumentException", "BusinessException", "PermissionDeniedException")),
                "方法内全部 throw 异常类型都应收集，实际: " + exceptions);
        assertEquals(3, exceptions.size(), "BusinessException 重复出现只计一次");
    }

    @Test
    void exceptionCollectionCappedAtFive() {
        CompilationUnit cu = StaticJavaParser.parse("""
                import org.springframework.web.bind.annotation.*;
                @RestController
                class Controller {
                    @PostMapping("/pay")
                    public String pay(int channel) {
                        if (channel == 1) throw new E1("a");
                        if (channel == 2) throw new E2("a");
                        if (channel == 3) throw new E3("a");
                        if (channel == 4) throw new E4("a");
                        if (channel == 5) throw new E5("a");
                        if (channel == 6) throw new E6("a");
                        return "ok";
                    }
                }
                """);

        List<EndpointInfo> endpoints = analyzer.extractEndpoints(cu, "Controller.java");

        assertEquals(5, endpoints.get(0).getExceptions().size(),
                "异常收集上限 5，防长方法撑爆上下文");
    }

    // ==================== A6: 业务规则噪音过滤 ====================

    @Test
    void noiseExceptionGuardsAreFilteredFromBusinessRules() {
        CompilationUnit cu = StaticJavaParser.parse("""
                class OrderService {
                    public void cancel(Order order) {
                        if (order == null) {
                            throw new NullPointerException("order is null");
                        }
                        if (order.getStatus() == OrderStatus.SHIPPED) {
                            throw new BusinessException("已发货订单不可取消");
                        }
                    }
                }
                """);

        List<String> warnings = new ArrayList<>();
        List<BusinessRule> rules = analyzer.extractBusinessRules(cu, "OrderService.java", warnings);

        assertEquals(1, rules.size(), "只有业务异常构成规则");
        assertTrue(rules.get(0).getRule().contains("BusinessException"),
                "规则应保留业务异常，实际: " + rules.get(0).getRule());
        assertEquals(1, warnings.size(), "过滤量应进 warnings 可观测");
        assertTrue(warnings.get(0).contains("已过滤 1 条"));
    }

    @Test
    void assertionGuardsFilteredButBusinessRulesKept() {
        CompilationUnit cu = StaticJavaParser.parse("""
                class PayService {
                    public void pay(Order order, int amount) {
                        if (amount <= 0) {
                            throw new IllegalArgumentException("金额非法");
                        }
                        if (order.getBalance() < amount) {
                            throw new InsufficientBalanceException("余额不足");
                        }
                    }
                }
                """);

        List<String> warnings = new ArrayList<>();
        List<BusinessRule> rules = analyzer.extractBusinessRules(cu, "PayService.java", warnings);

        assertEquals(1, rules.size(), "参数断言（IllegalArgumentException）是噪音，余额不足是业务规则");
        assertTrue(rules.get(0).getRule().contains("InsufficientBalanceException"));
    }

    // ==================== A18: 状态启发式扩展 ====================

    @Test
    void integerLiteralStateTransitionIsExtracted() {
        // setStatus(2) / == 1 魔法数形态——旧 looksLikeEnumConstant 对整数字面量返回 null，证据全丢
        CompilationUnit cu = StaticJavaParser.parse("""
                class OrderService {
                    public void confirm(Order order) {
                        if (order.getStatus() == 1) {
                            order.setStatus(2);
                        }
                    }
                }
                """);

        List<Map<String, Object>> evidence = analyzer.extractStateTransitions(cu, "OrderService.java");

        assertEquals(1, evidence.size());
        assertEquals("1", evidence.get(0).get("from"), "Integer 字面量条件应识别来源状态");
        assertEquals("2", evidence.get(0).get("to"), "Integer 字面量赋值应识别目标状态");
    }

    @Test
    void stringLiteralStateTransitionIsExtracted() {
        CompilationUnit cu = StaticJavaParser.parse("""
                class OrderService {
                    public void pay(Order order) {
                        if ("CREATED".equals(order.getStatus())) {
                            order.setStatus("PAID");
                        }
                    }
                }
                """);

        List<Map<String, Object>> evidence = analyzer.extractStateTransitions(cu, "OrderService.java");

        assertEquals(1, evidence.size());
        assertEquals("CREATED", evidence.get(0).get("from"));
        assertEquals("PAID", evidence.get(0).get("to"));
    }

    @Test
    void typeFieldIsRecognizedAsStateCarrier() {
        // order.type / userType / paymentType 是常见状态承载字段（旧实现只认 *status*）
        CompilationUnit cu = StaticJavaParser.parse("""
                class UserService {
                    public void upgrade(User user) {
                        if (user.getType() == UserType.NORMAL) {
                            user.setType(UserType.VIP);
                        }
                    }
                }
                """);

        List<Map<String, Object>> evidence = analyzer.extractStateTransitions(cu, "UserService.java");

        assertEquals(1, evidence.size(), "type 字段 setter/赋值应视为状态转换");
        assertEquals("type", evidence.get(0).get("field"));
        assertEquals("VIP", evidence.get(0).get("to"));
    }

    @Test
    void ordinaryFieldAssignmentIsNotStateTransition() {
        // setName("张三") 类普通字段赋值不进状态证据（isStateFieldName 门槛拦截）
        CompilationUnit cu = StaticJavaParser.parse("""
                class UserService {
                    public void rename(User user) {
                        if (user.getAge() > 18) {
                            user.setName("成年用户");
                        }
                    }
                }
                """);

        List<Map<String, Object>> evidence = analyzer.extractStateTransitions(cu, "UserService.java");

        assertTrue(evidence.isEmpty(), "name 类普通字段不应产生状态转换证据");
    }
}
