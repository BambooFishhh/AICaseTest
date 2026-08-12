package com.testagent.service;

import com.testagent.entity.TestCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.14: XmindService 单元测试——生成 XMind 后能逆向解析（round-trip）。
 */
class XmindServiceTest {

    private XmindService xmindService;

    @BeforeEach
    void setUp() throws Exception {
        xmindService = new XmindService();
        // 注入临时输出目录（绕过 @Value，保持纯单元测试）
        Field outputDirField = XmindService.class.getDeclaredField("outputDir");
        outputDirField.setAccessible(true);
        outputDirField.set(xmindService, Files.createTempDirectory("xmind-test").toString());
    }

    @Test
    void generateAndParse_roundTripKeepsTitles() throws Exception {
        TestCase tc = new TestCase();
        tc.setId("TC-001");
        tc.setTitle("创建订单-正向");
        tc.setModule("订单创建");
        tc.setType("positive");
        tc.setPriority("P0");
        tc.setPreconditions("[\"用户已登录\"]");
        tc.setSteps("[\"填写商品\",\"提交订单\"]");
        tc.setExpectedResults("[\"返回订单ID\"]");
        tc.setStructuredSteps("[]");

        String path = xmindService.generateXmind(List.of(tc), "测试项目");
        assertNotNull(path, "应返回生成文件路径");
        File file = new File(path);
        assertTrue(file.exists(), "生成的 XMind 文件应存在");

        List<TestCase> parsed;
        try (FileInputStream in = new FileInputStream(file)) {
            parsed = xmindService.parseXmind(in);
        }
        assertFalse(parsed.isEmpty(), "解析结果不应为空");
        boolean found = parsed.stream().anyMatch(p -> "创建订单-正向".equals(p.getTitle()));
        assertTrue(found, "round-trip 应能找到原用例标题");
    }
}
