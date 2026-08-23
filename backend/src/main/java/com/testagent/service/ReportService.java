package com.testagent.service;

import com.testagent.entity.ExecutionRecord;
import com.testagent.entity.ExecutionStep;
import com.testagent.repository.ExecutionRecordRepository;
import com.testagent.repository.ExecutionStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * v2.4: 测试执行报告生成服务。
 * 生成自包含 HTML 报告（内嵌 base64 截图）。
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** v7.2(R12): 报告版本收敛为单一常量（旧实现两处 footer 硬编码 v2.4，从未随迭代更新） */
    private static final String APP_VERSION = "v7.2";

    @Autowired
    private ExecutionRecordRepository recordRepo;

    @Autowired
    private ExecutionStepRepository stepRepo;

    /**
     * v7.2(R10): 通过率口径统一——分母 = passed + failed（跳过不计入，对齐 Allure 惯例）。
     * 旧实现分母含 skipped，产生"passed 徽章 + 0% 通过率"的自相矛盾呈现。
     */
    static double passRateOf(long passed, long failed) {
        long judged = passed + failed;
        return judged == 0 ? 0 : (double) passed / judged * 100;
    }

    /**
     * 生成单次执行报告。
     * @param executionId 执行记录 ID
     * @return 自包含 HTML 字符串
     */
    public String generateExecutionReport(String executionId) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter(16 * 1024);
            generateExecutionReport(executionId, sw);
            return sw.toString();
        } catch (java.io.IOException e) {
            // StringWriter 不会抛 IOException，防御性兜底
            throw new IllegalStateException("报告生成失败", e);
        }
    }

    /**
     * v7.12(R16): 流式生成单次执行报告——HTML 分段写出、截图逐张"读取→编码→写出→释放"。
     * 旧实现把全部截图 base64 聚在 StringBuilder 再 toString()，峰值 = 2×报告体积
     * （百步级 Agent 执行 + 双截图可达数百 MB）；流式后峰值 = 单截图 + base64 缓冲。
     * 自包含 base64 单文件交付语义不变（下载/分享行为零变化）。
     * @param executionId 执行记录 ID
     * @param out 输出目标（HTTP 响应 / StringWriter）
     */
    public void generateExecutionReport(String executionId, java.io.Writer out) throws java.io.IOException {
        ExecutionRecord record = recordRepo.findById(executionId).orElse(null);
        if (record == null) {
            out.write("<html><body><h2>报告不存在</h2><p>executionId=" + escapeHtml(executionId) + " 未找到。</p></body></html>");
            out.flush();
            return;
        }
        List<ExecutionStep> steps = stepRepo.findByExecutionIdOrderByStepIndexAsc(executionId);

        // 统计
        int totalSteps = steps.size();
        long passedSteps = steps.stream().filter(s -> "passed".equals(s.getResult())).count();
        long failedSteps = steps.stream().filter(s -> "failed".equals(s.getResult())).count();
        long skippedSteps = steps.stream().filter(s -> "skipped".equals(s.getResult())).count();
        double passRate = passRateOf(passedSteps, failedSteps);

        String duration = formatDuration(record.getStartTime(), record.getEndTime());

        out.write("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        out.write("<title>测试执行报告 - " + escapeHtml(safe(record.getTestCaseTitle())) + "</title>");
        out.write("<style>" + getInlineCss() + "</style></head><body>");
        out.flush();

        // 标题
        out.write("<div class=\"header\">");
        out.write("<h1>测试执行报告</h1>");
        out.write("<div class=\"meta\">报告生成时间：" + LocalDateTime.now() + "</div>");
        out.write("</div>");

        // 概览
        out.write("<h2>概览</h2>");
        out.write("<table class=\"overview\">");
        out.write("<tr><th>用例名称</th><td>" + escapeHtml(safe(record.getTestCaseTitle())) + "</td></tr>");
        out.write("<tr><th>执行 ID</th><td>" + escapeHtml(safe(record.getId())) + "</td></tr>");
        out.write("<tr><th>状态</th><td><span class=\"badge " + statusClass(record.getStatus()) + "\">"
                + escapeHtml(safe(record.getStatus())) + "</span></td></tr>");
        out.write("<tr><th>开始时间</th><td>" + safe(record.getStartTime()) + "</td></tr>");
        out.write("<tr><th>结束时间</th><td>" + safe(record.getEndTime()) + "</td></tr>");
        out.write("<tr><th>耗时</th><td>" + duration + "</td></tr>");
        out.write("<tr><th>执行模式</th><td>" + escapeHtml(safe(record.getMode())) + "</td></tr>");
        out.write("<tr><th>步骤数</th><td>" + totalSteps
                + "（通过 " + passedSteps
                + " / 失败 " + failedSteps
                + " / 跳过 " + skippedSteps + "）</td></tr>");
        out.write("<tr><th>通过率</th><td>" + String.format("%.1f%%", passRate)
                + (skippedSteps > 0 ? "（跳过 " + skippedSteps + " 步未计入分母）" : "")
                + "</td></tr>");
        out.write("<tr><th>摘要</th><td>" + escapeHtml(safe(record.getSummary())) + "</td></tr>");
        if (record.getErrorMessage() != null && !record.getErrorMessage().isBlank()) {
            out.write("<tr><th>错误信息</th><td class=\"error\">" + escapeHtml(record.getErrorMessage()) + "</td></tr>");
        }
        out.write("</table>");
        out.flush();

        // 步骤详情
        out.write("<h2>步骤详情</h2>");
        if (steps.isEmpty()) {
            out.write("<p>无步骤记录。</p>");
        } else {
            for (ExecutionStep step : steps) {
                out.write("<div class=\"step\">");
                out.write("<div class=\"step-header\">");
                out.write("<span class=\"step-index\">步骤 #" + step.getStepIndex() + "</span>");
                out.write("<span class=\"badge " + statusClass(step.getResult()) + "\">"
                        + escapeHtml(safe(step.getResult())) + "</span>");
                out.write("</div>");
                out.write("<table class=\"step-table\">");
                writeRow(out, "Action", escapeHtml(safe(step.getAction())));
                writeRow(out, "Target", escapeHtml(safe(step.getTarget())));
                writeRow(out, "Strategy", escapeHtml(safe(step.getStrategy())));
                if (step.getCoordinates() != null && !step.getCoordinates().isBlank()) {
                    writeRow(out, "Coordinates", escapeHtml(safe(step.getCoordinates())));
                }
                if (step.getError() != null && !step.getError().isBlank()) {
                    writeRow(out, "Error", "<span class=\"error\">" + escapeHtml(step.getError()) + "</span>");
                }
                out.write("</table>");

                // 截图（v7.9/R11 三态语义不变；v7.12/R16 逐张处理即写即释）
                String beforeBase64 = imageToBase64(step.getScreenshotBefore());
                String afterBase64 = imageToBase64(step.getScreenshotAfter());
                if (beforeBase64 != null || afterBase64 != null) {
                    out.write("<div class=\"screenshots\">");
                    writeShot(out, "操作前", beforeBase64, step.getScreenshotBefore());
                    writeShot(out, "操作后", afterBase64, step.getScreenshotAfter());
                    out.write("</div>");
                }
                out.write("</div>");
                out.flush();
            }
        }

        // 失败分析
        long failedCount = steps.stream().filter(s -> "failed".equals(s.getResult())).count();
        out.write("<h2>失败分析</h2>");
        if (failedCount == 0) {
            out.write("<p>无失败步骤。</p>");
        } else {
            out.write("<table class=\"fail-table\"><tr><th>步骤</th><th>Action</th><th>错误信息</th></tr>");
            for (ExecutionStep step : steps) {
                if ("failed".equals(step.getResult())) {
                    out.write("<tr><td>#" + step.getStepIndex() + "</td>");
                    out.write("<td>" + escapeHtml(safe(step.getAction())) + "</td>");
                    out.write("<td class=\"error\">" + escapeHtml(safe(step.getError())) + "</td></tr>");
                }
            }
            out.write("</table>");
        }

        out.write("<div class=\"footer\">AICaseTest " + APP_VERSION + " 报告</div>");
        out.write("</body></html>");
        out.flush();
    }

    /**
     * 生成批次执行报告。
     * @param batchId 批次 ID
     * @return 自包含 HTML 字符串
     */
    public String generateBatchReport(String batchId) {
        List<ExecutionRecord> records = recordRepo.findByBatchId(batchId);
        if (records == null || records.isEmpty()) {
            return "<html><body><h2>报告不存在</h2><p>batchId=" + escapeHtml(batchId) + " 未找到记录。</p></body></html>";
        }

        int total = records.size();
        long passed = records.stream().filter(r -> "passed".equals(r.getStatus())).count();
        long failed = records.stream().filter(r -> "failed".equals(r.getStatus())).count();
        long running = records.stream().filter(r -> "running".equals(r.getStatus())).count();
        long pending = records.stream().filter(r -> "pending".equals(r.getStatus())).count();
        long skipped = records.stream().filter(r -> "skipped".equals(r.getStatus())).count();
        // v7.2(R12): 分母 = passed + failed——running/pending/cancelled/skipped 均未形成判定，
        // 旧实现"报告生成瞬间还有任务在跑"会把通过率稀释
        double passRate = passRateOf(passed, failed);
        long undecided = total - passed - failed;

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        html.append("<title>批次执行报告 - ").append(escapeHtml(batchId)).append("</title>");
        html.append("<style>").append(getInlineCss()).append("</style></head><body>");

        html.append("<div class=\"header\">");
        html.append("<h1>批次执行报告</h1>");
        html.append("<div class=\"meta\">批次 ID：").append(escapeHtml(batchId))
            .append("，报告生成时间：").append(LocalDateTime.now().toString()).append("</div>");
        html.append("</div>");

        // 汇总
        html.append("<h2>批次汇总</h2>");
        html.append("<table class=\"overview\">");
        html.append("<tr><th>批次 ID</th><td>").append(escapeHtml(batchId)).append("</td></tr>");
        html.append("<tr><th>用例总数</th><td>").append(total).append("</td></tr>");
        html.append("<tr><th>通过</th><td>").append(passed).append("</td></tr>");
        html.append("<tr><th>失败</th><td>").append(failed).append("</td></tr>");
        html.append("<tr><th>运行中</th><td>").append(running).append("</td></tr>");
        html.append("<tr><th>待执行</th><td>").append(pending).append("</td></tr>");
        html.append("<tr><th>已跳过</th><td>").append(skipped).append("</td></tr>");
        html.append("<tr><th>通过率</th><td>").append(String.format("%.1f%%", passRate))
            .append(undecided > 0 ? "（" + undecided + " 条未判定记录未计入分母）" : "")
            .append("</td></tr>");
        html.append("</table>");

        // 用例列表
        html.append("<h2>用例执行列表</h2>");
        html.append("<table class=\"list-table\"><tr><th>执行 ID</th><th>用例名称</th><th>状态</th><th>开始时间</th><th>耗时</th><th>摘要</th></tr>");
        for (ExecutionRecord r : records) {
            html.append("<tr>");
            html.append("<td>").append(escapeHtml(safe(r.getId()))).append("</td>");
            html.append("<td>").append(escapeHtml(safe(r.getTestCaseTitle()))).append("</td>");
            html.append("<td><span class=\"badge ").append(statusClass(r.getStatus())).append("\">")
                .append(escapeHtml(safe(r.getStatus()))).append("</span></td>");
            html.append("<td>").append(safe(r.getStartTime())).append("</td>");
            html.append("<td>").append(formatDuration(r.getStartTime(), r.getEndTime())).append("</td>");
            html.append("<td>").append(escapeHtml(safe(r.getSummary()))).append("</td>");
            html.append("</tr>");
        }
        html.append("</table>");

        // 失败列表
        html.append("<h2>失败用例分析</h2>");
        if (failed == 0) {
            html.append("<p>无失败用例。</p>");
        } else {
            html.append("<table class=\"fail-table\"><tr><th>执行 ID</th><th>用例名称</th><th>错误信息</th></tr>");
            for (ExecutionRecord r : records) {
                if ("failed".equals(r.getStatus())) {
                    html.append("<tr>");
                    html.append("<td>").append(escapeHtml(safe(r.getId()))).append("</td>");
                    html.append("<td>").append(escapeHtml(safe(r.getTestCaseTitle()))).append("</td>");
                    html.append("<td class=\"error\">").append(escapeHtml(safe(r.getErrorMessage()))).append("</td>");
                    html.append("</tr>");
                }
            }
            html.append("</table>");
        }

        html.append("<div class=\"footer\">AICaseTest ").append(APP_VERSION).append(" 报告</div>");
        html.append("</body></html>");
        return html.toString();
    }

    /**
     * 将图片文件转换为 base64 data URL。
     * v7.9(R11): 三态返回——null=路径为空（无截图，不渲染）；""=路径非空但读取失败
     * （截图丢失，渲染告警占位）；其他=base64 正常渲染。旧实现读取失败静默返回空串，
     * 多实例部署（截图在另一实例本地盘）下报告缺图且完全不可知。
     */
    private String imageToBase64(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(path));
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            log.warn("Evidence file missing, path={}: {}", path, e.getMessage());
            return "";
        }
    }

    /**
     * v7.9(R11): 渲染单个截图位——正常图片 / 丢失告警占位 / 无截图（null 不渲染）。
     * v7.12(R16): 改为 Writer 直写（三态语义不变）。
     */
    private void writeShot(java.io.Writer out, String label, String base64, String path) throws java.io.IOException {
        if (base64 == null) {
            return;
        }
        if (base64.isEmpty()) {
            out.write("<div class=\"shot shot-missing\"><div class=\"shot-label\">" + label + "</div>"
                    + "<div class=\"missing-text\">⚠ 截图文件缺失：" + escapeHtml(safe(path))
                    + "<br>多实例部署时请将 outputs 目录配置为共享卷（详见 README 部署说明）</div></div>");
            return;
        }
        out.write("<div class=\"shot\"><div class=\"shot-label\">" + label + "</div>");
        out.write("<img src=\"" + base64 + "\" onclick=\"this.classList.toggle('zoomed')\" alt=\""
                + label + "截图\"></div>");
    }

    /**
     * HTML 转义。
     */
    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** null 安全的字符串化 */
    private String safe(Object o) {
        return o == null ? "" : o.toString();
    }

    /** 计算耗时描述 */
    private String formatDuration(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) return "-";
        try {
            Duration d = Duration.between(start, end);
            long seconds = d.getSeconds();
            if (seconds < 60) return seconds + " 秒";
            return (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
        } catch (Exception e) {
            return "-";
        }
    }

    /** 状态对应的 CSS class */
    private String statusClass(String status) {
        if (status == null) return "badge-neutral";
        return switch (status) {
            case "passed" -> "badge-passed";
            case "failed" -> "badge-failed";
            case "running" -> "badge-running";
            case "skipped" -> "badge-skipped";
            default -> "badge-neutral";
        };
    }

    /** 追加一行两列表格（v7.12/R16: Writer 直写） */
    private void writeRow(java.io.Writer out, String key, String value) throws java.io.IOException {
        out.write("<tr><th>" + key + "</th><td>" + value + "</td></tr>");
    }

    /** 内联 CSS 样式 */
    private String getInlineCss() {
        return "body{font-family:'Segoe UI',Arial,sans-serif;margin:0;padding:24px;background:#f5f7fa;color:#222;}"
                + ".header{background:linear-gradient(135deg,#4a6cf7,#5b8def);color:#fff;padding:24px 32px;border-radius:8px;margin-bottom:24px;}"
                + ".header h1{margin:0 0 8px 0;font-size:24px;}"
                + ".header .meta{font-size:13px;opacity:.9;}"
                + "h2{color:#2c3e50;border-left:4px solid #4a6cf7;padding-left:10px;margin:28px 0 14px 0;font-size:18px;}"
                + "table{border-collapse:collapse;width:100%;background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.08);border-radius:4px;overflow:hidden;margin-bottom:16px;}"
                + "th,td{padding:10px 14px;text-align:left;border-bottom:1px solid #eee;font-size:14px;vertical-align:top;}"
                + "th{background:#fafbfc;font-weight:600;color:#555;width:160px;}"
                + "tr:last-child td{border-bottom:none;}"
                + ".overview th{width:160px;background:#f0f4ff;}"
                + ".badge{display:inline-block;padding:3px 10px;border-radius:12px;font-size:12px;font-weight:600;color:#fff;}"
                + ".badge-passed{background:#52c41a;}"
                + ".badge-failed{background:#f5222d;}"
                + ".badge-running{background:#faad14;}"
                + ".badge-skipped{background:#bfbfbf;}"
                + ".badge-neutral{background:#8c8c8c;}"
                + ".error{color:#f5222d;word-break:break-word;}"
                + ".step{background:#fff;border:1px solid #e8e8e8;border-radius:6px;padding:14px 18px;margin-bottom:14px;}"
                + ".step-header{display:flex;align-items:center;gap:10px;margin-bottom:10px;}"
                + ".step-index{font-weight:600;color:#4a6cf7;}"
                + ".step-table th{width:120px;background:#fafafa;}"
                + ".screenshots{display:flex;gap:16px;margin-top:12px;flex-wrap:wrap;}"
                + ".shot{flex:1;min-width:280px;}"
                + ".shot-missing .missing-text{padding:16px;background:#fff8e6;border:1px dashed #e6a23c;border-radius:4px;color:#b88230;font-size:13px;line-height:1.6;word-break:break-all;}"
                + ".shot-label{font-size:12px;color:#888;margin-bottom:6px;}"
                + ".shot img{max-width:100%;border:1px solid #ddd;border-radius:4px;cursor:zoom-in;display:block;}"
                + ".shot img.zoomed{position:fixed;top:5%;left:5%;width:90%;z-index:9999;cursor:zoom-out;border:4px solid #fff;box-shadow:0 0 20px rgba(0,0,0,.4);}"
                + ".list-table th,.fail-table th{background:#f0f4ff;color:#333;}"
                + ".list-table td,.fail-table td{font-size:13px;}"
                + ".footer{text-align:center;color:#999;font-size:12px;margin-top:32px;padding:16px;}";
    }
}
