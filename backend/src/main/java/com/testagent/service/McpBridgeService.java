package com.testagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.dto.LocateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * v2.1: MCP 桥接服务 — 多模态视觉识别。
 * 接收截图路径 + 自然语言描述，调用多模态 LLM，返回结构化位置 JSON。
 * 不操作浏览器，只做视觉识别。
 */
@Service
public class McpBridgeService {

    private static final Logger log = LoggerFactory.getLogger(McpBridgeService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private LlmService llmService;

    private static final String SYSTEM_PROMPT = """
        你是页面控件视觉识别专家。请看截图，找到用户描述的控件位置。
        返回纯 JSON（不要 markdown 代码块），格式固定：
        {"found": true/false, "bbox": [x1,y1,x2,y2], "click_center": {"x": 0, "y": 0}, "element_text": "", "confidence": 0.0}
        - found: 是否找到目标控件
        - bbox: 控件边界框坐标 [左上x, 左上y, 右下x, 右下y]
        - click_center: 点击中心坐标
        - element_text: 控件上的文字
        - confidence: 置信度 0-1
        如果找不到目标控件，found 设为 false，其他字段留空或为 0。
        """;

    /**
     * 多模态元素定位。
     * @param imagePath 截图文件路径
     * @param elementDesc 元素自然语言描述，如"找到页面登录按钮"
     * @return LocateResult 定位结果
     */
    public LocateResult multimodalElementLocate(String imagePath, String elementDesc) {
        if (!llmService.isConfigured()) {
            return LocateResult.fail("LLM 未配置，无法调用多模态识别");
        }

        try {
            // 1. 读取图片 → base64
            byte[] imageBytes = Files.readAllBytes(Path.of(imagePath));
            String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);

            // 2. 调多模态 LLM
            String userText = "请在截图中找到以下控件: " + elementDesc;
            String response = llmService.chatWithImage(SYSTEM_PROMPT, userText, imageBase64);

            // 3. 解析 JSON
            return parseLocateResult(response);

        } catch (Exception e) {
            log.warn("MCP multimodal_element_locate failed: {}", e.getMessage());
            return LocateResult.fail("MCP 调用异常: " + e.getMessage());
        }
    }

    private LocateResult parseLocateResult(String response) {
        try {
            // 提取 JSON（可能被 markdown 包裹）
            String json = response.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf("{");
                int end = json.lastIndexOf("}");
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }

            JsonNode root = objectMapper.readTree(json);

            boolean found = root.path("found").asBoolean(false);
            int[] bbox = null;
            if (root.has("bbox") && root.get("bbox").isArray() && root.get("bbox").size() == 4) {
                bbox = new int[4];
                for (int i = 0; i < 4; i++) {
                    bbox[i] = root.get("bbox").get(i).asInt();
                }
            }

            int clickX = 0, clickY = 0;
            JsonNode center = root.path("click_center");
            if (!center.isMissingNode()) {
                clickX = center.path("x").asInt(0);
                clickY = center.path("y").asInt(0);
            }

            return LocateResult.builder()
                    .found(found)
                    .bbox(bbox)
                    .clickX(clickX)
                    .clickY(clickY)
                    .elementText(root.path("element_text").asText(""))
                    .confidence(root.path("confidence").asDouble(0))
                    .build();

        } catch (Exception e) {
            log.warn("Failed to parse MCP result: {} | raw: {}", e.getMessage(), response);
            return LocateResult.fail("MCP 返回 JSON 解析失败: " + e.getMessage());
        }
    }
}
