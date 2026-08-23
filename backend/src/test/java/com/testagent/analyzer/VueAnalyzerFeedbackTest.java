package com.testagent.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.6(G20层3): VueAnalyzer 用户反馈文案提取测试。
 * 业务背景：前端错误展示文案（ElMessage.error 等）此前从未被分析器采集，
 * LLM 无对照表可翻译，expected 常出现 HTTP 码/字段名而非页面可感知现象。
 */
class VueAnalyzerFeedbackTest {

    @TempDir
    Path tempDir;

    private VueAnalyzer analyzer() {
        return new VueAnalyzer();
    }

    @Test
    void elMessageTextsAreExtracted() throws IOException {
        writeVueFile("OrderList.vue", """
                <template><div>订单</div></template>
                <script setup>
                import { ElMessage } from 'element-plus'
                const del = async () => {
                  await api.deleteOrder()
                  ElMessage.success('删除成功')
                }
                const fail = () => { ElMessage.error('库存不足，无法下单') }
                </script>
                """);

        List<Map<String, Object>> texts = analyzer().extractFeedbackTexts(tempDir.toFile(), new ArrayList<>());

        assertEquals(2, texts.size());
        assertTrue(texts.stream().anyMatch(t ->
                "success".equals(t.get("type")) && "删除成功".equals(t.get("text"))));
        assertTrue(texts.stream().anyMatch(t ->
                "error".equals(t.get("type")) && "库存不足，无法下单".equals(t.get("text"))));
    }

    @Test
    void thisDollarMessageAndPlainMessageAreExtracted() throws IOException {
        // this.$message.error（Vue2 选项式）/ Message.warning（按需引入）
        writeVueFile("User.vue", """
                <script>
                export default {
                  methods: {
                    ban() { this.$message.error('该用户已被禁用') },
                    warn() { Message.warning('操作有风险') }
                  }
                }
                </script>
                """);

        List<Map<String, Object>> texts = analyzer().extractFeedbackTexts(tempDir.toFile(), new ArrayList<>());

        assertTrue(texts.stream().anyMatch(t -> "该用户已被禁用".equals(t.get("text"))));
        assertTrue(texts.stream().anyMatch(t ->
                "warning".equals(t.get("type")) && "操作有风险".equals(t.get("text"))));
    }

    @Test
    void duplicateTextsAcrossFilesAreDeduped() throws IOException {
        writeVueFile("A.vue", """
                <script setup>
                ElMessage.error('删除失败')
                </script>
                """);
        writeVueFile("B.vue", """
                <script setup>
                ElMessage.error('删除失败')
                ElMessage.info('提示信息')
                </script>
                """);

        List<Map<String, Object>> texts = analyzer().extractFeedbackTexts(tempDir.toFile(), new ArrayList<>());

        assertEquals(2, texts.size(), "跨文件重复文案应去重（type+text 相同）");
    }

    @Test
    void variableArgumentIsNotCollected() throws IOException {
        // ElMessage.error(variable) —— 无字面量，不收集
        writeVueFile("C.vue", """
                <script setup>
                const show = (msg) => { ElMessage.error(msg) }
                </script>
                """);

        List<Map<String, Object>> texts = analyzer().extractFeedbackTexts(tempDir.toFile(), new ArrayList<>());

        assertTrue(texts.isEmpty());
    }

    @Test
    void overLimitIsTruncatedWithWarning() throws IOException {
        // 101 条不同文案 → 截断为 100 + warning
        StringBuilder sb = new StringBuilder("<script setup>\n");
        for (int i = 0; i < 101; i++) {
            sb.append("ElMessage.info('提示").append(i).append("')\n");
        }
        sb.append("</script>");
        writeVueFile("Many.vue", sb.toString());

        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> texts = analyzer().extractFeedbackTexts(tempDir.toFile(), warnings);

        assertEquals(100, texts.size());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("截断"));
    }

    private void writeVueFile(String name, String content) throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src").resolve(name), content);
    }
}
