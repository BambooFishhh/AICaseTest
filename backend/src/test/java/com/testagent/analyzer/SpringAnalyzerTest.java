package com.testagent.analyzer;

import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.BusinessRule;
import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.analyzer.result.EntityInfo;
import com.testagent.analyzer.result.EnumInfo;
import com.testagent.service.LlmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void ruleExtractionMarksRulesSourceWithoutLlm() throws IOException {
        writeBackendProject(tempDir);
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(false);
        SpringAnalyzer analyzer = new SpringAnalyzer();
        ReflectionTestUtils.setField(analyzer, "llmService", llmService);

        BackendResult result = analyzer.analyze(tempDir.toString());

        assertEquals(2, result.getEndpoints().size());
        assertTrue(result.getEndpoints().stream()
                .allMatch(e -> List.of("rules").equals(e.getSources())));
        assertEquals(1, result.getEntities().size());
        assertEquals(1, result.getBusinessRules().size());
        verify(llmService, never()).chat(anyString(), anyString(), anyDouble());
    }

    @Test
    void llmEnhancementEnrichesExistingFactsWithoutOverwriting() throws IOException {
        writeBackendProject(tempDir);
        String llmJson = """
                {
                  "endpointEnhancements": [{
                    "method": "POST",
                    "path": "/api/orders",
                    "function": "HackedController.create",
                    "file": "hacked.java",
                    "description": "create order",
                    "parameters": [{"name": "body", "in": "body", "type": "String", "required": true, "description": "order json"}],
                    "requestBody": "order creation request body",
                    "permissions": ["ROLE_USER"],
                    "validation": ["order amount must be positive"]
                  }],
                  "entityEnhancements": [{
                    "name": "Order",
                    "description": "order entity",
                    "fieldConstraints": [{"name": "status", "type": "String", "required": true, "maxLength": 32, "description": "order status"}],
                    "relationships": []
                  }],
                  "supplementalBusinessRules": [{
                    "file": "src/main/java/com/example/OrderService.java",
                    "function": "createOrder",
                    "rule": "order amount must be positive",
                    "ruleType": "validation"
                  }],
                  "enumEnhancements": [{
                    "name": "OrderStatus",
                    "description": "order status enum",
                    "values": [{"name": "CREATED", "description": "created"}]
                  }]
                }
                """;
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(true);
        when(llmService.chat(anyString(), anyString(), anyDouble())).thenReturn(llmJson);
        SpringAnalyzer analyzer = new SpringAnalyzer();
        ReflectionTestUtils.setField(analyzer, "llmService", llmService);

        BackendResult result = analyzer.analyze(tempDir.toString());

        EndpointInfo post = result.getEndpoints().stream()
                .filter(e -> "POST".equals(e.getMethod()) && "/api/orders".equals(e.getPath()))
                .findFirst()
                .orElseThrow();
        assertEquals("OrderController.createOrder", post.getFunction());
        assertTrue(post.getFile().endsWith("OrderController.java"));
        assertEquals("create order", post.getDescription());
        assertEquals(1, post.getParameters().size());
        assertEquals("order creation request body", post.getRequestBody());
        assertEquals(List.of("ROLE_USER"), post.getPermissions());
        assertEquals(List.of("order amount must be positive"), post.getValidation());
        assertEquals(List.of("rules", "llm"), post.getSources());

        EndpointInfo get = result.getEndpoints().stream()
                .filter(e -> "GET".equals(e.getMethod()))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("rules"), get.getSources());

        EntityInfo order = result.getEntities().stream()
                .filter(e -> "Order".equals(e.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("order entity", order.getDescription());
        assertEquals(1, order.getFieldConstraints().size());
        assertEquals(List.of("rules", "llm"), order.getSources());

        BusinessRule llmRule = result.getBusinessRules().stream()
                .filter(r -> "order amount must be positive".equals(r.getRule()))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("llm"), llmRule.getSources());

        EnumInfo orderStatus = result.getEnums().stream()
                .filter(e -> "OrderStatus".equals(e.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("order status enum", orderStatus.getDescription());
        assertEquals("created", orderStatus.getValues().get(0).getDescription());
        assertEquals(List.of("rules", "llm"), orderStatus.getSources());
        verify(llmService).chat(anyString(), anyString(), anyDouble());
    }

    @Test
    void invalidLlmResponseFallsBackToRuleResults() throws IOException {
        writeBackendProject(tempDir);
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(true);
        when(llmService.chat(anyString(), anyString(), anyDouble())).thenReturn("not a json object");
        SpringAnalyzer analyzer = new SpringAnalyzer();
        ReflectionTestUtils.setField(analyzer, "llmService", llmService);

        BackendResult result = analyzer.analyze(tempDir.toString());

        assertNotNull(result);
        assertEquals(2, result.getEndpoints().size());
        assertTrue(result.getEndpoints().stream()
                .allMatch(e -> List.of("rules").equals(e.getSources())));
    }

    private void writeBackendProject(Path root) throws IOException {
        write(root, "src/main/java/com/example/OrderController.java", """
                package com.example;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/orders")
                public class OrderController {
                    @GetMapping("/{id}")
                    public String getOrder(@PathVariable Long id) {
                        return "ok";
                    }

                    @PostMapping
                    public String createOrder(@RequestBody String body) {
                        return "ok";
                    }
                }
                """);
        write(root, "src/main/java/com/example/Order.java", """
                package com.example;

                import jakarta.persistence.Entity;

                @Entity
                public class Order {
                    private String status;
                    private Integer amount;

                    public String getStatus() { return status; }
                    public void setStatus(String status) { this.status = status; }
                    public Integer getAmount() { return amount; }
                    public void setAmount(Integer amount) { this.amount = amount; }
                }
                """);
        write(root, "src/main/java/com/example/OrderService.java", """
                package com.example;

                public class OrderService {
                    public void createOrder(int stock) {
                        if (stock <= 0) {
                            throw new IllegalArgumentException("stock exhausted");
                        }
                    }
                }
                """);
        write(root, "src/main/java/com/example/OrderStatus.java", """
                package com.example;

                public enum OrderStatus {
                    CREATED, PAID
                }
                """);
    }

    private void write(Path root, String relPath, String content) throws IOException {
        Path file = root.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
