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
 * v7.10(A12): apiCalls 全量扫描单测——旧实现只扫 src/api 目录，
 * 组件内直书 axios.get('/api/order/' + id) 全漏，状态机跨端证据不完整。
 * 新实现扫描全部 .vue/.js/.ts（跳过 node_modules/dist/.git），按 (method+url) 去重，上限 100。
 */
class VueAnalyzerApiCallsTest {

    @TempDir
    Path tempDir;

    private final VueAnalyzer analyzer = new VueAnalyzer();

    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private String url(Map<String, Object> call) {
        return String.valueOf(call.get("url"));
    }

    @Test
    void axiosCallInsideComponentIsExtracted() throws IOException {
        // 组件内直书 axios 调用（非 src/api 目录）——旧实现全漏
        write(tempDir.resolve("src/components/OrderList.vue"), """
                <template><div>订单</div></template>
                <script setup>
                import axios from 'axios'
                const load = () => axios.get('/api/order/list')
                const remove = (id) => axios.delete('/api/order/' + id)
                </script>
                """);

        List<Map<String, Object>> calls = analyzer.extractApiCalls(tempDir.toFile(), new ArrayList<>());

        assertEquals(2, calls.size(), "组件内直书 axios 调用应被识别");
        assertTrue(calls.stream().anyMatch(c ->
                "get".equals(c.get("method")) && "/api/order/list".equals(url(c))));
        assertTrue(calls.stream().anyMatch(c ->
                "delete".equals(c.get("method")) && "/api/order/".equals(url(c))));
    }

    @Test
    void apiDirectoryCallsStillExtracted() throws IOException {
        // src/api 目录的调用照常识别（全量扫描是超集）
        write(tempDir.resolve("src/api/order.js"), """
                import request from '@/utils/request'
                export function createOrder(data) {
                  return request({ url: '/api/order/create', method: 'post', data })
                }
                """);

        List<Map<String, Object>> calls = analyzer.extractApiCalls(tempDir.toFile(), new ArrayList<>());

        assertTrue(calls.stream().anyMatch(c -> "/api/order/create".equals(url(c))),
                "src/api 目录调用仍应识别");
    }

    @Test
    void duplicateMethodAndUrlPairsAreDeduplicated() throws IOException {
        // 同一 (method, url) 在多文件/多调用点出现 → 只计一次
        write(tempDir.resolve("src/api/order.js"), """
                import axios from 'axios'
                export const a = () => axios.get('/api/order/list')
                """);
        write(tempDir.resolve("src/components/A.vue"), """
                <script setup>
                import axios from 'axios'
                const load = () => axios.get('/api/order/list')
                </script>
                """);
        write(tempDir.resolve("src/components/B.vue"), """
                <script setup>
                import axios from 'axios'
                const load = () => axios.get('/api/order/list')
                </script>
                """);

        List<Map<String, Object>> calls = analyzer.extractApiCalls(tempDir.toFile(), new ArrayList<>());

        long listCount = calls.stream().filter(c -> "/api/order/list".equals(url(c))).count();
        assertEquals(1, listCount, "同 (method+url) 跨文件去重");
    }

    @Test
    void sameUrlDifferentMethodNotDeduplicated() throws IOException {
        // method 不同不算重复
        write(tempDir.resolve("src/api/order.js"), """
                import axios from 'axios'
                export const a = () => axios.get('/api/order/1')
                export const b = () => axios.delete('/api/order/1')
                """);

        List<Map<String, Object>> calls = analyzer.extractApiCalls(tempDir.toFile(), new ArrayList<>());

        assertEquals(2, calls.size(), "GET 与 DELETE 同 URL 是两个不同调用");
    }

    @Test
    void nodeModulesAreSkipped() throws IOException {
        write(tempDir.resolve("node_modules/lib/index.js"), """
                module.exports = { fetchAll: () => fetch('/api/whatever') }
                """);
        write(tempDir.resolve("src/App.vue"), """
                <script setup>
                import axios from 'axios'
                const load = () => axios.get('/api/real')
                </script>
                """);

        List<Map<String, Object>> calls = analyzer.extractApiCalls(tempDir.toFile(), new ArrayList<>());

        assertEquals(1, calls.size(), "node_modules 应跳过");
        assertEquals("/api/real", url(calls.get(0)));
    }

    @Test
    void overHundredCallsTruncatedWithWarning() throws IOException {
        // 101 个不同 URL → 截断为 100 + warning 记录
        StringBuilder sb = new StringBuilder("import axios from 'axios'\n");
        for (int i = 0; i < 101; i++) {
            sb.append("export const f").append(i).append(" = () => axios.get('/api/item/").append(i).append("')\n");
        }
        write(tempDir.resolve("src/api/big.js"), sb.toString());

        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> calls = analyzer.extractApiCalls(tempDir.toFile(), warnings);

        assertEquals(100, calls.size(), "上限 100 截断");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("apiCalls 超上限"));
    }
}
