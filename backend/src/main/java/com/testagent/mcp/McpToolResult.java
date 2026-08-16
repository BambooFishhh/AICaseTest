package com.testagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// v5.14: MCP 工具调用结果，附带 usage 等元数据
@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpToolResult {

    private String text;

    private JsonNode metadata;
}
