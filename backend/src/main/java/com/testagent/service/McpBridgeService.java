package com.testagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.dto.LocateResult;
import com.testagent.mcp.McpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * v2.1: MCP 桥接服务 — 多模态视觉识别。
 * v2.2: 重构为通过 MCP 协议调用独立 MCP Server。
 * 不操作浏览器，只做视觉识别。
 */
@Service
public class McpBridgeService {

    private static final Logger log = LoggerFactory.getLogger(McpBridgeService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private McpClient mcpClient;

    /**
     * v2.2: 通过 MCP 协议调用多模态元素定位。
     * @param imagePath 截图文件路径
     * @param elementDesc 元素自然语言描述，如"找到页面登录按钮"
     * @return LocateResult 定位结果
     */
    public LocateResult multimodalElementLocate(String imagePath, String elementDesc) {
        if (!mcpClient.isAvailable()) {
            log.warn("MCP Server 不可用，视觉识别降级");
            return LocateResult.fail("MCP Server 未启动");
        }

        try {
            // 通过 MCP 协议调用 multimodal_element_locate 工具
            String response = mcpClient.callTool("multimodal_element_locate",
                    Map.of("image_path", imagePath, "element_desc", elementDesc));

            return parseLocateResult(response);

        } catch (Exception e) {
            log.warn("MCP multimodal_element_locate failed: {}", e.getMessage());
            return LocateResult.fail("MCP 调用异常: " + e.getMessage());
        }
    }

    private LocateResult parseLocateResult(String response) {
        try {
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
