package com.testagent.service;

import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.14: CsvExporter 单元测试——表头、行内容、UTF-8 BOM。
 */
class CsvExporterTest {

    @Test
    void toCsv_containsHeaderAndRowValues() {
        TestCase tc = new TestCase();
        tc.setId("TC-001");
        tc.setTitle("创建订单-正向");
        tc.setModule("订单创建");
        tc.setType("positive");
        tc.setPriority("P0");
        tc.setPreconditions("[\"用户已登录\"]");
        tc.setSteps("[\"填写商品\",\"提交订单\"]");
        tc.setExpectedResults("[\"返回订单ID\"]");

        byte[] csv = CsvExporter.toCsv(List.of(tc));
        // UTF-8 BOM
        assertTrue(csv.length >= 3
                && csv[0] == (byte) 0xEF && csv[1] == (byte) 0xBB && csv[2] == (byte) 0xBF,
                "CSV 应包含 UTF-8 BOM");

        String text = new String(csv, StandardCharsets.UTF_8);
        assertTrue(text.contains("id,title,module,type,priority"), "应包含表头");
        assertTrue(text.contains("TC-001"), "应包含用例编号");
        assertTrue(text.contains("创建订单-正向"), "应包含用例标题");
        assertTrue(text.contains("填写商品 ; 提交订单"), "步骤列表应以分号拼接");
    }
}
