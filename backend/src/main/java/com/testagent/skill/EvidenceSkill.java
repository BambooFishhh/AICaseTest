package com.testagent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * v2.0: 证据存储 Skill
 * 接收结构化证据数据，写入本地 Markdown 文档。
 */
@Component
public class EvidenceSkill {

    private static final Logger log = LoggerFactory.getLogger(EvidenceSkill.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 保存测试证据到文件。
     * @param evidenceData 证据数据（stepDescription, strategy, coordinates, screenshotBefore, screenshotAfter, result, error 等）
     * @return 文件路径
     */
    public String saveTestEvidence(Map<String, Object> evidenceData) {
        String executionId = (String) evidenceData.getOrDefault("executionId", "unknown");
        String evidenceDir = "outputs/evidence";
        String fileName = executionId + ".md";
        Path filePath = Paths.get(evidenceDir, fileName);

        try {
            Files.createDirectories(filePath.getParent());
            String content = formatEvidenceMarkdown(evidenceData);
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            log.info("Evidence saved to {}", filePath);
            return filePath.toString();
        } catch (IOException e) {
            log.error("Failed to save evidence", e);
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private String formatEvidenceMarkdown(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 测试执行证据\n\n");
        sb.append("| 字段 | 值 |\n|------|----|\n");
        sb.append("| 执行ID | ").append(data.getOrDefault("executionId", "")).append(" |\n");
        sb.append("| 用例标题 | ").append(data.getOrDefault("testCaseTitle", "")).append(" |\n");
        sb.append("| 执行状态 | ").append(data.getOrDefault("result", "")).append(" |\n");
        sb.append("| 开始时间 | ").append(data.getOrDefault("startTime", "")).append(" |\n");
        sb.append("| 结束时间 | ").append(data.getOrDefault("endTime", "")).append(" |\n");
        sb.append("| 摘要 | ").append(data.getOrDefault("summary", "")).append(" |\n");

        if (data.get("errorMessage") != null) {
            sb.append("\n## 错误信息\n\n```\n").append(data.get("errorMessage")).append("\n```\n");
        }

        Object steps = data.get("steps");
        if (steps != null) {
            sb.append("\n## 步骤详情\n\n");
            if (steps instanceof Iterable) {
                int idx = 1;
                for (Object step : (Iterable<?>) steps) {
                    if (step instanceof Map) {
                        Map<String, Object> s = (Map<String, Object>) step;
                        sb.append("### 步骤 ").append(idx++).append(": ").append(s.getOrDefault("action", "")).append("\n\n");
                        sb.append("| 字段 | 值 |\n|------|----|\n");
                        sb.append("| 目标 | ").append(s.getOrDefault("target", "")).append(" |\n");
                        sb.append("| 执行策略 | ").append(s.getOrDefault("strategy", "")).append(" |\n");
                        sb.append("| 结果 | ").append(s.getOrDefault("result", "")).append(" |\n");
                        sb.append("| 坐标 | ").append(s.getOrDefault("coordinates", "")).append(" |\n");
                        sb.append("| 操作前截图 | ").append(s.getOrDefault("screenshotBefore", "")).append(" |\n");
                        sb.append("| 操作后截图 | ").append(s.getOrDefault("screenshotAfter", "")).append(" |\n");
                        if (s.get("error") != null) {
                            sb.append("| 错误 | ").append(s.get("error")).append(" |\n");
                        }
                        sb.append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }
}
