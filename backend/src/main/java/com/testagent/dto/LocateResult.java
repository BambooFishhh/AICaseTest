package com.testagent.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * v2.1: MCP 多模态视觉识别返回结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocateResult {
    /** 是否找到目标控件 */
    private boolean found;
    /** 边界框 [x1, y1, x2, y2] */
    private int[] bbox;
    /** 点击中心 X 坐标 */
    private int clickX;
    /** 点击中心 Y 坐标 */
    private int clickY;
    /** 控件文本 */
    private String elementText;
    /** 置信度 0-1 */
    private double confidence;
    /** 异常信息（MCP 调用失败时填充） */
    private String error;

    public static LocateResult fail(String error) {
        return LocateResult.builder().found(false).error(error).build();
    }
}
