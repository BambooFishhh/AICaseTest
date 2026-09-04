package com.testagent.analyzer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.testagent.analyzer.result.EndpointInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v13.7 低优先级收尾单测：
 *   1. @RequestMapping 多值 method 展开为多端点（旧实现只取第一个，{GET,POST} 只剩 GET）；
 *   2. interface 常量枚举（字段隐含 static final）不再全漏；
 *   3. 单值注解与 <3 常量类回归不变。
 */
class SpringAnalyzerV137Test {

    private final SpringAnalyzer analyzer = new SpringAnalyzer();

    @Test
    void multiValueMethodExpandsToMultipleEndpoints() {
        CompilationUnit cu = StaticJavaParser.parse(
                "import org.springframework.web.bind.annotation.*;\n"
                        + "@RestController\n"
                        + "@RequestMapping(\"/api/demo\")\n"
                        + "class DemoController {\n"
                        + "  @RequestMapping(value = \"/multi\", method = {RequestMethod.GET, RequestMethod.POST})\n"
                        + "  Object multi() { return null; }\n"
                        + "  @GetMapping(\"/one\")\n"
                        + "  Object one() { return null; }\n"
                        + "}\n");

        List<EndpointInfo> endpoints = analyzer.extractEndpoints(cu, "Demo.java");

        assertEquals(3, endpoints.size(), "{GET,POST} 应展开为 2 端点 + @GetMapping 1 端点");
        long multi = endpoints.stream().filter(e -> e.getPath().contains("/multi")).count();
        assertEquals(2, multi, "多值 method 应产生 2 个端点");
        assertTrue(endpoints.stream().anyMatch(e -> e.getPath().contains("/multi")
                        && "GET".equals(e.getMethod())),
                "GET 端点应存在");
        assertTrue(endpoints.stream().anyMatch(e -> e.getPath().contains("/multi")
                        && "POST".equals(e.getMethod())),
                "POST 端点应存在（旧实现只剩 GET）");
        assertTrue(endpoints.stream().anyMatch(e -> e.getPath().contains("/one")
                        && "GET".equals(e.getMethod())), "@GetMapping 回归");
    }

    @Test
    void interfaceConstantsCollectedAsEnum() {
        CompilationUnit cu = StaticJavaParser.parse(
                "interface UserType {\n"
                        + "  int ADMIN = 1;\n"
                        + "  int MEMBER = 2;\n"
                        + "  int GUEST = 3;\n"
                        + "}\n");

        var enums = analyzer.extractEnums(cu, "UserType.java");

        assertEquals(1, enums.size(), "interface 常量枚举应被收集（旧实现全漏）");
        assertEquals("UserType", enums.get(0).getName());
        assertEquals("constants", enums.get(0).getType());
        assertEquals(3, enums.get(0).getValues().size(), "字段隐含 static final 应被识别");
    }

    /** 回归：普通类 <3 个常量仍不收录（噪音阈值不变） */
    @Test
    void classWithFewConstantsStillSkipped() {
        CompilationUnit cu = StaticJavaParser.parse(
                "class Config {\n"
                        + "  public static final String A = \"a\";\n"
                        + "  public static final String B = \"b\";\n"
                        + "}\n");

        assertTrue(analyzer.extractEnums(cu, "Config.java").isEmpty(), "<3 常量阈值回归");
    }
}
