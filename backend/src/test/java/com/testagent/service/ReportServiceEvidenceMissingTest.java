package com.testagent.service;

import com.testagent.entity.ExecutionRecord;
import com.testagent.entity.ExecutionStep;
import com.testagent.repository.ExecutionRecordRepository;
import com.testagent.repository.ExecutionStepRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v7.9(R11): 报告截图三态渲染验证。
 * 旧实现 imageToBase64 读取失败静默返回空串——多实例部署（截图在另一实例本地盘）
 * 下报告缺图且完全不可知。新实现：null=无截图（不渲染）；""=丢失（渲染告警占位）；
 * base64=正常渲染。
 */
class ReportServiceEvidenceMissingTest {

    /** 1x1 透明 PNG 的 base64 */
    private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    @TempDir
    Path tempDir;

    @SuppressWarnings("unchecked")
    private ReportService serviceWithSteps(List<ExecutionStep> steps) {
        ReportService service = new ReportService();
        ExecutionRecordRepository recordRepo = mock(ExecutionRecordRepository.class);
        ExecutionStepRepository stepRepo = mock(ExecutionStepRepository.class);
        ReflectionTestUtils.setField(service, "recordRepo", recordRepo);
        ReflectionTestUtils.setField(service, "stepRepo", stepRepo);

        ExecutionRecord record = new ExecutionRecord();
        record.setId("exec-1");
        record.setTestCaseTitle("证据三态用例");
        record.setStatus("passed");
        when(recordRepo.findById("exec-1")).thenReturn(Optional.of(record));
        when(stepRepo.findByExecutionIdOrderByStepIndexAsc("exec-1")).thenReturn(steps);
        return service;
    }

    private ExecutionStep step(int index, String before, String after) {
        return ExecutionStep.builder()
                .id("step-" + index)
                .executionId("exec-1")
                .stepIndex(index)
                .action("click")
                .target("提交按钮")
                .strategy("visual")
                .result("passed")
                .screenshotBefore(before)
                .screenshotAfter(after)
                .build();
    }

    private int count(String html, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = html.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    void threeStateRenderingInOneReport() throws Exception {
        Path goodFile = tempDir.resolve("good.png");
        Files.write(goodFile, PNG_BYTES);

        List<ExecutionStep> steps = List.of(
                step(1, null, null),                                    // 无截图：不渲染
                step(2, "/nonexistent/before.png", "/nonexistent/after.png"), // 丢失：两个告警占位
                step(3, goodFile.toString(), null));                    // 正常：一张图片

        String html = serviceWithSteps(steps).generateExecutionReport("exec-1");

        // step2 两个占位 + step3 一个图片位 = 3 个截图位；step1 不渲染（CSS 中的 .shot-label 不计入）
        assertEquals(3, count(html, "class=\"shot-label\""), "无截图步骤不应渲染截图位");
        assertEquals(2, count(html, "截图文件缺失"), "两个坏路径都应渲染丢失告警");
        assertEquals(1, count(html, "data:image/png;base64,"), "一个有效文件渲染一张图");
        assertTrue(html.contains("/nonexistent/before.png"), "告警占位应包含丢失路径便于排查");
    }

    @Test
    void imageToBase64ThreeStateSemantics() throws Exception {
        ReportService service = new ReportService();
        Path goodFile = tempDir.resolve("ok.png");
        Files.write(goodFile, PNG_BYTES);

        assertNull(invoke(service, null), "路径为 null → 无截图");
        assertNull(invoke(service, "  "), "路径为空白 → 无截图");
        assertEquals("", invoke(service, "/nonexistent/x.png"), "路径非空但读取失败 → 丢失");
        String base64 = invoke(service, goodFile.toString());
        assertTrue(base64.startsWith("data:image/png;base64,"), "正常文件 → base64");
        assertEquals(PNG_BYTES.length, Base64.getDecoder().decode(base64.substring(22)).length);
    }

    /**
     * v7.12(R16): Writer 流式版与 String 版输出内容等价——
     * String 版本身委托 Writer 版（StringWriter），此处直写 Writer 断言三态渲染语义不变。
     */
    @Test
    void writerVersionProducesEquivalentContent() throws Exception {
        Path goodFile = tempDir.resolve("good2.png");
        Files.write(goodFile, PNG_BYTES);

        List<ExecutionStep> steps = List.of(
                step(1, null, null),
                step(2, "/nonexistent/before.png", null),
                step(3, goodFile.toString(), goodFile.toString()));

        java.io.StringWriter out = new java.io.StringWriter();
        serviceWithSteps(steps).generateExecutionReport("exec-1", out);
        String html = out.toString();

        // 三个截图位：step2 一个丢失占位 + step3 两张图
        assertEquals(3, count(html, "class=\"shot-label\""));
        assertEquals(1, count(html, "截图文件缺失"));
        assertEquals(2, count(html, "data:image/png;base64,"), "step3 双截图正常渲染");
        assertTrue(html.endsWith("</body></html>"), "流式输出必须完整收尾");
        assertTrue(html.contains("AICaseTest"), "footer 完整");
    }

    private String invoke(ReportService service, String path) {
        return (String) ReflectionTestUtils.invokeMethod(service, "imageToBase64", path);
    }
}
