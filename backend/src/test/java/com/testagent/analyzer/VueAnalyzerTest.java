package com.testagent.analyzer;

import com.testagent.analyzer.result.FrontendResult;
import com.testagent.common.BusinessComponentPolicy;
import com.testagent.service.LlmResultCacheService;
import com.testagent.service.LlmService;
import com.testagent.service.TelemetryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VueAnalyzerTest {

    @TempDir
    Path tempDir;

    private VueAnalyzer analyzerWithoutLlm() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(false);
        return buildAnalyzer(llmService, mock(LlmResultCacheService.class));
    }

    private VueAnalyzer buildAnalyzer(LlmService llmService) {
        return buildAnalyzer(llmService, mock(LlmResultCacheService.class));
    }

    private VueAnalyzer buildAnalyzer(LlmService llmService, LlmResultCacheService cacheService) {
        VueAnalyzer analyzer = new VueAnalyzer();
        ReflectionTestUtils.setField(analyzer, "llmService", llmService);
        ReflectionTestUtils.setField(analyzer, "llmResultCacheService", cacheService);
        TelemetryService telemetryService = mock(TelemetryService.class);
        // bindPhase mock 默认不执行 task——组件摘要路径需要真实执行 supplier 才能走到 LLM/缓存
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(2)).get())
                .when(telemetryService).bindPhase(any(), any(), any());
        ReflectionTestUtils.setField(analyzer, "telemetryService", telemetryService);
        ReflectionTestUtils.setField(analyzer, "businessComponentPolicy", mock(BusinessComponentPolicy.class));
        ReflectionTestUtils.setField(analyzer, "llmConcurrency", 1);
        return analyzer;
    }

    // v7.4(A8): 一个 .vue 文件含多个 rules 块（多表单/多 rules 对象）时必须全部合并解析，
    // 此前 rs.find() 只取第一个块，后续字段的校验规则全部丢失
    @Test
    void multipleRulesBlocksMergedForForm() throws IOException {
        writeVueFile(tempDir, "OrderForm.vue", """
                <template>
                  <el-form>
                    <el-form-item label="用户名" prop="username"><el-input v-model="username" /></el-form-item>
                    <el-form-item label="邮箱" prop="email"><el-input v-model="email" /></el-form-item>
                  </el-form>
                </template>
                <script>
                export default {
                  data() {
                    return {
                      form1rules: {
                        username: [{ required: true, message: '必填', trigger: 'blur' }]
                      },
                      form2rules: {
                        email: [{ required: true, message: '必填', trigger: 'blur' }]
                      }
                    }
                  }
                }
                </script>
                """);
        VueAnalyzer analyzer = analyzerWithoutLlm();

        FrontendResult result = analyzer.analyze(tempDir.toString());

        Map<String, Object> form = result.getForms().stream()
                .filter(f -> "OrderForm".equals(f.get("component")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) form.get("fields");
        Map<String, Object> username = fieldByName(fields, "username");
        Map<String, Object> email = fieldByName(fields, "email");
        // 两个块的字段校验都必须解析到（第二个块此前会丢失）
        assertEquals(Boolean.TRUE, username.get("required"));
        assertEquals(Boolean.TRUE, email.get("required"));
        // 多块合并行为可观测（C1）
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("2 个 rules 块，已全部合并解析")));
    }

    // v7.4(A7): rules 值中的模板字符串（反引号 + 嵌套 {} 与 ${}）不得让括号配对提前闭合，
    // 否则模板串之后的字段校验全部丢失
    @Test
    void templateStringInRulesDoesNotBreakParsing() throws IOException {
        writeVueFile(tempDir, "CodeForm.vue", """
                <template>
                  <el-form>
                    <el-form-item label="编码" prop="code"><el-input v-model="code" /></el-form-item>
                    <el-form-item label="数量" prop="amount"><el-input-number v-model="amount" /></el-form-item>
                  </el-form>
                </template>
                <script>
                export default {
                  data() {
                    return {
                      rules: {
                        code: [{ pattern: `^[A-Z]{3}-\\d{2}$`, message: `格式错误 ${'{'}code${'}'}`, trigger: 'blur' }],
                        amount: [{ required: true, min: 1, max: 99, message: '必填', trigger: 'blur' }]
                      }
                    }
                  }
                }
                </script>
                """);
        VueAnalyzer analyzer = analyzerWithoutLlm();

        FrontendResult result = analyzer.analyze(tempDir.toString());

        Map<String, Object> form = result.getForms().stream()
                .filter(f -> "CodeForm".equals(f.get("component")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) form.get("fields");
        // 模板串中的 {3} 会让旧实现提前闭合 rules 块，amount 的校验将全部丢失
        Map<String, Object> amount = fieldByName(fields, "amount");
        assertEquals(Boolean.TRUE, amount.get("required"));
        @SuppressWarnings("unchecked")
        List<String> amountRules = (List<String>) amount.get("rules");
        assertTrue(amountRules.contains("min:1"));
        assertTrue(amountRules.contains("max:99"));
        // 模板串中的 pattern 也能被提取（不再截断）
        Map<String, Object> code = fieldByName(fields, "code");
        @SuppressWarnings("unchecked")
        List<String> codeRules = (List<String>) code.get("rules");
        assertTrue(codeRules.stream().anyMatch(r -> r.contains("pattern")));
    }

    // v7.4(A10): LLM 补充同组件表单时按字段级合并——正则已有字段保留、LLM 新字段追加，
    // 此前组件已存在即整条丢弃，正则漏掉的字段全部丢失
    @Test
    void llmSupplementMergesFieldsNotReplace() throws IOException {
        writeVueFile(tempDir, "OrderForm.vue", """
                <template>
                  <el-form>
                    <el-form-item label="名称" prop="name"><el-input v-model="name" /></el-form-item>
                  </el-form>
                </template>
                <script>
                export default {
                  data() {
                    return {
                      rules: {
                        name: [{ required: true, message: '必填', trigger: 'blur' }]
                      }
                    }
                  }
                }
                </script>
                """);
        String llmJson = """
                {
                  "supplementalForms": [
                    {
                      "component": "OrderForm",
                      "file": "OrderForm.vue",
                      "fields": [
                        {"name": "name", "type": "el-input", "label": "LLM覆盖标签", "required": false, "rules": []},
                        {"name": "remark", "type": "el-input", "label": "备注", "required": true, "rules": ["required"]}
                      ]
                    }
                  ]
                }
                """;
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(true);
        when(llmService.chat(anyString(), anyString(), anyDouble())).thenReturn(llmJson);
        VueAnalyzer analyzer = buildAnalyzer(llmService);

        FrontendResult result = analyzer.analyze(tempDir.toString());

        Map<String, Object> form = result.getForms().stream()
                .filter(f -> "OrderForm".equals(f.get("component")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) form.get("fields");
        // 正则已有字段保留（含校验规则与原始 label，不被 LLM 覆盖）
        Map<String, Object> name = fieldByName(fields, "name");
        assertEquals("名称", name.get("label"));
        assertEquals(Boolean.TRUE, name.get("required"));
        // LLM 新字段追加（此前整条丢弃，remark 丢失）
        Map<String, Object> remark = fieldByName(fields, "remark");
        assertNotNull(remark);
        assertEquals(Boolean.TRUE, remark.get("required"));
    }

    // v7.4(C1): rules 块括号不配对（文件截半）不再静默丢失，必须产生告警
    @Test
    void unbalancedRulesBlockReportsWarning() throws IOException {
        writeVueFile(tempDir, "BrokenForm.vue", """
                <template>
                  <el-form>
                    <el-form-item label="名称" prop="name"><el-input v-model="name" /></el-form-item>
                  </el-form>
                </template>
                <script>
                export default {
                  data() {
                    return {
                      rules: {
                        name: [{ required: true, message: '必填', trigger: 'blur'
                </script>
                """);
        VueAnalyzer analyzer = analyzerWithoutLlm();

        FrontendResult result = analyzer.analyze(tempDir.toString());

        assertNotNull(result.getWarnings());
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("rules 块括号不配对") && w.contains("BrokenForm.vue")));
    }

    // v7.5(A11): 组件摘要 LLM 结果缓存——同源码组件二次分析命中缓存不重复调 LLM
    @Test
    void componentSummaryCacheAvoidsRepeatLlmCalls() throws IOException {
        String vue = """
                <template>
                  <div class="order-list">
                    <el-button @click="loadOrders">刷新</el-button>
                  </div>
                </template>
                <script>
                import axios from 'axios';
                export default {
                  name: 'OrderList',
                  data() { return { orders: [] }; },
                  methods: {
                    async loadOrders() {
                      const res = await axios.get('/api/orders');
                      this.orders = res.data;
                    }
                  }
                }
                </script>
                """;
        Path dirA = tempDir.resolve("a");
        Path dirB = tempDir.resolve("b");
        writeVueFile(dirA, "OrderList.vue", vue);
        writeVueFile(dirB, "OrderList.vue", vue);

        String summaryJson = """
                {"summary": "订单列表页，支持刷新加载", "interactions": ["点击刷新"],
                 "apiCalls": ["/api/orders"], "stateOps": [], "routeNavigations": [], "keywords": ["订单"]}
                """;
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(true);
        when(llmService.chat(anyString(), anyString(), anyDouble())).thenReturn(summaryJson);
        LlmResultCacheService cacheService = mock(LlmResultCacheService.class);
        when(cacheService.get(eq("component_summary"), anyString(), anyString()))
                .thenReturn(null, summaryJson);

        VueAnalyzer analyzer = new VueAnalyzer();
        ReflectionTestUtils.setField(analyzer, "llmService", llmService);
        ReflectionTestUtils.setField(analyzer, "llmResultCacheService", cacheService);
        TelemetryService telemetryService = mock(TelemetryService.class);
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(2)).get())
                .when(telemetryService).bindPhase(any(), any(), any());
        ReflectionTestUtils.setField(analyzer, "telemetryService", telemetryService);
        BusinessComponentPolicy policy = mock(BusinessComponentPolicy.class);
        when(policy.needsLlmSummary(any())).thenReturn(true);
        ReflectionTestUtils.setField(analyzer, "businessComponentPolicy", policy);
        ReflectionTestUtils.setField(analyzer, "llmConcurrency", 1);

        // 第一次分析：组件摘要调 1 次 LLM 并写缓存（另有 1 次 enhanceWithLlm 表单补充调用）
        FrontendResult first = analyzer.analyze(dirA.toString());
        verify(llmService, times(2)).chat(anyString(), anyString(), anyDouble());
        verify(cacheService, times(1)).put(eq("component_summary"), anyString(), anyString(), eq(summaryJson));
        assertTrue(first.getComponentSummaries().stream()
                .anyMatch(s -> "OrderList".equals(s.get("component"))
                        && String.valueOf(s.get("summary")).contains("订单列表")));

        // 第二次分析（同内容不同目录）：组件摘要命中缓存，LLM 调用仅剩 enhanceWithLlm 的 1 次
        // （首次 2 次 = 1 组件摘要 + 1 enhanceWithLlm；本次 = 1 enhanceWithLlm，摘要走缓存）
        FrontendResult second = analyzer.analyze(dirB.toString());
        verify(llmService, times(3)).chat(anyString(), anyString(), anyDouble());
        verify(cacheService, times(1)).put(anyString(), anyString(), anyString(), anyString());
        assertTrue(second.getComponentSummaries().stream()
                .anyMatch(s -> "OrderList".equals(s.get("component"))
                        && String.valueOf(s.get("summary")).contains("订单列表")));
    }

    private Map<String, Object> fieldByName(List<Map<String, Object>> fields, String name) {
        return fields.stream()
                .filter(f -> name.equals(f.get("name")))
                .findFirst()
                .orElseThrow();
    }

    private void writeVueFile(Path root, String fileName, String content) throws IOException {
        Path file = root.resolve("src/views/" + fileName);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
