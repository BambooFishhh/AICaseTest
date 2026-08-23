package com.testagent.analyzer;

import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.service.LlmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v7.7: 规则层参数提取（A5）与 LLM 补充接口源码校验（A4a）单测。
 */
class SpringAnalyzerParameterTest {

    @TempDir
    Path tempDir;

    // ==================== A5: 规则层参数提取 ====================

    @Test
    void parameterAnnotationsExtractedWithoutLlm() throws IOException {
        writeParameterController(tempDir);
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(false);
        SpringAnalyzer analyzer = new SpringAnalyzer();
        ReflectionTestUtils.setField(analyzer, "llmService", llmService);

        BackendResult result = analyzer.analyze(tempDir.toString());

        // GET /api/orders/search：@RequestParam(name="kw", required=false, defaultValue="test")
        EndpointInfo search = findEndpoint(result, "GET", "/api/orders/search");
        assertNotNull(search, "规则层应解析出搜索接口");
        assertEquals(1, search.getParameters().size());
        Map<String, Object> kw = search.getParameters().get(0);
        assertEquals("kw", kw.get("name"));
        assertEquals("query", kw.get("in"));
        assertEquals("String", kw.get("type"));
        assertEquals(false, kw.get("required"));
        assertEquals("test", kw.get("defaultValue"));

        // POST /api/orders/{id}/items：@PathVariable + @RequestBody
        EndpointInfo addItem = findEndpoint(result, "POST", "/api/orders/{id}/items");
        assertNotNull(addItem);
        assertEquals(2, addItem.getParameters().size());
        Map<String, Object> id = addItem.getParameters().get(0);
        assertEquals("id", id.get("name"));
        assertEquals("path", id.get("in"));
        assertEquals(true, id.get("required"));
        Map<String, Object> body = addItem.getParameters().get(1);
        assertEquals("body", body.get("in"));
        assertEquals("String", body.get("type"));
        // requestBody 类型由规则层写入
        assertEquals("String", addItem.getRequestBody());

        // 无注解参数（如 ServletRequest）跳过
        EndpointInfo plain = findEndpoint(result, "GET", "/api/orders/plain");
        assertNotNull(plain);
        assertTrue(plain.getParameters().isEmpty(), "无注解参数应跳过");
    }

    // ==================== A4a: LLM 补充接口源码存在性校验 ====================

    @Test
    void supplementalEndpointWithSourceEvidenceAccepted() throws IOException {
        writeParameterController(tempDir);
        String llmJson = """
                {
                  "supplementalEndpoints": [
                    {
                      "method": "GET",
                      "path": "/api/orders/export",
                      "function": "ParameterController.exportOrders",
                      "file": "ParameterController.java",
                      "description": "导出订单"
                    },
                    {
                      "method": "GET",
                      "path": "/api/hacked/list",
                      "function": "HackedController.list",
                      "file": "hacked.java",
                      "description": "不存在的接口"
                    }
                  ]
                }
                """;
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(true);
        when(llmService.chat(anyString(), anyString(), anyDouble())).thenReturn(llmJson);
        SpringAnalyzer analyzer = new SpringAnalyzer();
        ReflectionTestUtils.setField(analyzer, "llmService", llmService);

        BackendResult result = analyzer.analyze(tempDir.toString());

        // function 含已知类名（ParameterController）→ 收
        EndpointInfo exported = findEndpoint(result, "GET", "/api/orders/export");
        assertNotNull(exported, "有源码证据的补充接口应被接受");
        assertEquals(List.of("llm"), exported.getSources());
        // 类名与路径前缀均无证据 → 丢弃且告警可观测
        assertTrue(result.getEndpoints().stream()
                        .noneMatch(e -> "/api/hacked/list".equals(e.getPath())),
                "无源码证据的补充接口必须丢弃");
        assertTrue(result.getWarnings().stream()
                        .anyMatch(w -> w.contains("未通过源码校验已丢弃")),
                "丢弃行为必须告警可观测");
    }

    @Test
    void supplementalEndpointMatchingKnownPathPrefixAccepted() throws IOException {
        writeParameterController(tempDir);
        // path 前缀命中已知控制器 /api/orders（function 留空——只走前缀证据链）
        String llmJson = """
                {
                  "supplementalEndpoints": [
                    {
                      "method": "GET",
                      "path": "/api/orders/statistics",
                      "function": "",
                      "file": "",
                      "description": "订单统计"
                    }
                  ]
                }
                """;
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(true);
        when(llmService.chat(anyString(), anyString(), anyDouble())).thenReturn(llmJson);
        SpringAnalyzer analyzer = new SpringAnalyzer();
        ReflectionTestUtils.setField(analyzer, "llmService", llmService);

        BackendResult result = analyzer.analyze(tempDir.toString());

        assertNotNull(findEndpoint(result, "GET", "/api/orders/statistics"),
                "路径以已知控制器前缀开头的补充接口应被接受");
    }

    private EndpointInfo findEndpoint(BackendResult result, String method, String path) {
        return result.getEndpoints().stream()
                .filter(e -> method.equals(e.getMethod()) && path.equals(e.getPath()))
                .findFirst()
                .orElse(null);
    }

    private void writeParameterController(Path root) throws IOException {
        write(root, "src/main/java/com/example/ParameterController.java", """
                package com.example;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/orders")
                public class ParameterController {

                    @GetMapping("/search")
                    public String search(@RequestParam(name = "kw", required = false, defaultValue = "test") String keyword) {
                        return "ok";
                    }

                    @PostMapping("/{id}/items")
                    public String addItem(@PathVariable Long id, @RequestBody String item) {
                        return "ok";
                    }

                    @GetMapping("/plain")
                    public String plain(String raw) {
                        return "ok";
                    }
                }
                """);
    }

    private void write(Path root, String relPath, String content) throws IOException {
        Path file = root.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
