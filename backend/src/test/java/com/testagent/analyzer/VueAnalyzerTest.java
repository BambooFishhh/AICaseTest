package com.testagent.analyzer;

import com.testagent.analyzer.result.FrontendResult;
import com.testagent.common.BusinessComponentPolicy;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VueAnalyzerTest {

    @TempDir
    Path tempDir;

    private VueAnalyzer analyzerWithoutLlm() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(false);
        return buildAnalyzer(llmService);
    }

    private VueAnalyzer buildAnalyzer(LlmService llmService) {
        VueAnalyzer analyzer = new VueAnalyzer();
        ReflectionTestUtils.setField(analyzer, "llmService", llmService);
        ReflectionTestUtils.setField(analyzer, "telemetryService", mock(TelemetryService.class));
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
